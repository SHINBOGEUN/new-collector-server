package net.vivans.dcim.module.ingest.lora.application;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.lora.domain.LoraIdType;
import net.vivans.dcim.module.ingest.lora.infrastructure.ManagerLoraConfigClient;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraDeviceLookupResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraEndpointResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraModelPointResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraOverridePointResponse;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerConfigUnavailableException;
import net.vivans.dcim.module.ingest.mqtt.config.LoraMqttProperties;
import net.vivans.dcim.module.ingest.mqtt.domain.MqttExternalIdNormalizer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manager의 LoRa 설정(endpoint/모델매핑/override)을 TTL로 캐시한다.
 * 우선순위: device별 override → device model 기본 매핑 → 없으면 미매핑.
 * 캐시 미스(신규 등록 직후 등)는 Manager에 즉시 단건 조회로 보완한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoraConfigCache {

    private final ManagerLoraConfigClient managerLoraConfigClient;
    private final LoraMqttProperties properties;

    private final AtomicReference<Map<String, LoraResolvedDevice>> endpointsByKey =
            new AtomicReference<>(Map.of());
    private final AtomicReference<Map<Integer, Map<String, LoraMappingRule>>> modelMappingsByModelId =
            new AtomicReference<>(Map.of());
    private final AtomicReference<Map<Integer, Map<String, LoraMappingRule>>> overridesByDeviceId =
            new AtomicReference<>(Map.of());
    /** 한 번이라도 Manager 설정을 받아왔는지. 조회 실패와 "설정이 비어 있음"을 구분한다. */
    private final AtomicBoolean loaded = new AtomicBoolean(false);
    /** 유형 무관 활성 식별값 수. 0이어야만 수집 대상이 정말 없는 상태다. */
    private final AtomicInteger identityCount = new AtomicInteger(0);

    @PostConstruct
    public void initialLoad() {
        refresh();
    }

    /**
     * 세 설정을 모두 받은 뒤에만 캐시를 교체한다. 중간에 실패하면 직전 설정을 그대로 두어
     * Manager 재배포·일시 장애로 수집이 끊기지 않게 한다.
     */
    @Scheduled(fixedDelayString = "${ingest.mqtt.lora.config-cache-refresh-ms:300000}")
    public void refresh() {
        try {
            int identities = managerLoraConfigClient.countEnabledIdentities();

            Map<String, LoraResolvedDevice> endpoints = new HashMap<>();
            for (LoraEndpointResponse endpoint : managerLoraConfigClient.findAllEnabledEndpoints()) {
                String key = key(endpoint.idType(), endpoint.normalizedExternalId());
                endpoints.put(key, new LoraResolvedDevice(
                        endpoint.deviceId(), endpoint.deviceName(), endpoint.deviceModelId(), endpoint.deviceModelName()));
            }

            Map<Integer, Map<String, LoraMappingRule>> modelMappings = new HashMap<>();
            for (LoraModelPointResponse point : managerLoraConfigClient.findAllEnabledModelMappings()) {
                modelMappings
                        .computeIfAbsent(point.deviceModelId(), ignored -> new HashMap<>())
                        .put(point.payloadField(), new LoraMappingRule(
                                point.payloadField(), point.pointName(), point.dataPointTypeCode(),
                                point.unit(), point.scale(), point.valueMap()));
            }

            Map<Integer, Map<String, LoraMappingRule>> overrides = new HashMap<>();
            for (LoraOverridePointResponse point : managerLoraConfigClient.findAllEnabledOverrides()) {
                overrides
                        .computeIfAbsent(point.deviceId(), ignored -> new HashMap<>())
                        .put(point.payloadField(), new LoraMappingRule(
                                point.payloadField(), point.pointName(), point.dataPointTypeCode(),
                                point.unit(), point.scale(), point.valueMap()));
            }

            endpointsByKey.set(Map.copyOf(endpoints));
            modelMappingsByModelId.set(Map.copyOf(modelMappings));
            overridesByDeviceId.set(Map.copyOf(overrides));
            identityCount.set(identities);
            loaded.set(true);

            log.info("[LORA_CONFIG_CACHE_REFRESH] identities={} endpoints={} models={} overrideDevices={}",
                    identities, endpoints.size(), modelMappings.size(), overrides.size());
        } catch (ManagerConfigUnavailableException exception) {
            log.warn("[LORA_CONFIG_CACHE_REFRESH_SKIPPED] target={} reason={} keptEndpoints={} loaded={}",
                    exception.getTarget(), exception.getMessage(), endpointsByKey.get().size(), loaded.get());
        } catch (Exception exception) {
            log.warn("[LORA_CONFIG_CACHE_REFRESH_ERROR] exception={} message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    /** 설정을 한 번이라도 받아왔는지. false면 수집 대상 유무를 판단할 수 없다. */
    public boolean isLoaded() {
        return loaded.get();
    }

    /** 유형 무관(LoRa·MAC 등) 활성 식별값이 하나라도 있는지. */
    public boolean hasAnyIdentity() {
        return identityCount.get() > 0;
    }

    public Optional<LoraResolvedDevice> resolveDevice(LoraIdType idType, String externalId) {
        String normalized;
        try {
            normalized = MqttExternalIdNormalizer.normalize(idType.name(), externalId);
        } catch (IllegalArgumentException exception) {
            // 수신 payload에서 뽑은 값이라 형식이 깨질 수 있다. 호출 측이 미등록 장비로 처리하도록 둔다.
            log.debug("[LORA_INVALID_EXTERNAL_ID] idType={} externalId={} message={}",
                    idType, externalId, exception.getMessage());
            return Optional.empty();
        }
        LoraResolvedDevice cached = endpointsByKey.get().get(key(idType, normalized));
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<LoraDeviceLookupResponse> fallback = managerLoraConfigClient.resolve(idType, externalId);
        return fallback.map(response -> new LoraResolvedDevice(
                response.deviceId(), response.deviceName(), response.deviceModelId(), response.deviceModelName()));
    }

    /** override 우선, 없으면 model 기본 매핑. 둘 다 없으면 empty (호출 측에서 미매핑 오류로 처리). */
    public Optional<LoraMappingRule> resolveMapping(Integer deviceModelId, String payloadField) {
        return resolveMapping(null, deviceModelId, payloadField);
    }

    public Optional<LoraMappingRule> resolveMapping(Integer deviceId, Integer deviceModelId, String payloadField) {
        Map<String, LoraMappingRule> deviceOverrides = deviceId == null ? null : overridesByDeviceId.get().get(deviceId);
        if (deviceOverrides != null && deviceOverrides.containsKey(payloadField)) {
            return Optional.of(deviceOverrides.get(payloadField));
        }
        Map<String, LoraMappingRule> modelMappings = modelMappingsByModelId.get().get(deviceModelId);
        if (modelMappings != null && modelMappings.containsKey(payloadField)) {
            return Optional.of(modelMappings.get(payloadField));
        }
        return Optional.empty();
    }

    /** device의 override 매핑이 정의한 payload_field 전체 (model 기본 매핑과 합쳐 순회할 때 사용) */
    public java.util.Set<String> overrideFieldsOf(Integer deviceId) {
        Map<String, LoraMappingRule> deviceOverrides = overridesByDeviceId.get().get(deviceId);
        return deviceOverrides == null ? java.util.Set.of() : deviceOverrides.keySet();
    }

    public java.util.Set<String> modelFieldsOf(Integer deviceModelId) {
        Map<String, LoraMappingRule> modelMappings = modelMappingsByModelId.get().get(deviceModelId);
        return modelMappings == null ? java.util.Set.of() : modelMappings.keySet();
    }

    /** 활성 LoRa(devEUI/deviceName) 매핑이 있는지. 구독 시작 판단은 {@link #hasAnyIdentity()}를 쓴다. */
    public boolean hasConfiguredEndpoint() {
        return !endpointsByKey.get().isEmpty();
    }

    private static String key(LoraIdType idType, String normalizedExternalId) {
        return idType + "|" + normalizedExternalId;
    }
}
