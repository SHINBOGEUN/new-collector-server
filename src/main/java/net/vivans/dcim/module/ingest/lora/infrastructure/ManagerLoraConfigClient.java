package net.vivans.dcim.module.ingest.lora.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.lora.domain.LoraIdType;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraApiListResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraApiResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraDeviceLookupResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraEndpointResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraModelPointResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraOverridePointResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraMqttSourceResponse;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraMqttSourceStatusReportRequest;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerConfigUnavailableException;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerServiceProperties;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

/**
 * 공통 MQTT 설정을 기존 LoRa 처리 모델로 변환한다. LoRa 소스 관리는 기존 API를 유지한다.
 *
 * 설정 조회가 실패하면 빈 목록 대신 {@link ManagerConfigUnavailableException}을 던진다.
 * 빈 목록으로 바꾸면 호출 측이 "설정이 없다"로 읽어 정상 수집까지 중단한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagerLoraConfigClient {

    private final RestClient managerRestClient;
    private final ManagerServiceProperties properties;
    private final net.vivans.dcim.module.ingest.manager.infrastructure.ManagerMqttClient mqttClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public List<LoraEndpointResponse> findAllEnabledEndpoints() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        try {
            return mqttClient.identities().stream()
                    .filter(item -> item.enabled() && ("DEV_EUI".equals(item.identityType()) || "DEVICE_NAME".equals(item.identityType())))
                    .map(item -> new LoraEndpointResponse(item.identityId(), item.deviceId(), item.deviceName(),
                            item.deviceModelId(), item.deviceModelName(), LoraIdType.valueOf(item.identityType()),
                            item.externalId(), item.normalizedExternalId(), item.enabled())).toList();
        } catch (Exception exception) {
            log.warn("[LORA_CONFIG_FETCH_ERROR] target=endpoints exception={} message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw new ManagerConfigUnavailableException("endpoints", exception);
        }
    }

    /**
     * 유형과 무관한 활성 식별값 수. MQTT 구독을 시작할지 판단할 때 사용한다.
     * LoRa(DEV_EUI/DEVICE_NAME)만 세면 MAC만 등록된 환경에서 구독이 시작되지 않는다.
     */
    public int countEnabledIdentities() {
        if (!properties.isEnabled()) {
            return 0;
        }
        try {
            return (int) mqttClient.identities().stream()
                    .filter(item -> item.enabled() && item.deviceId() != null)
                    .count();
        } catch (Exception exception) {
            log.warn("[LORA_CONFIG_FETCH_ERROR] target=identities exception={} message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw new ManagerConfigUnavailableException("identities", exception);
        }
    }

    public List<LoraModelPointResponse> findAllEnabledModelMappings() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        try {
            LoraApiListResponse<com.fasterxml.jackson.databind.JsonNode> response = managerRestClient.get()
                    .uri("/api/manager/mqtt/mappings/models/bulk")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.status() != 200 || response.data() == null) {
                throw new IllegalStateException("Invalid manager model mappings response");
            }
            return response.data().stream()
                    .filter(item -> "DEVICE".equals(item.path("componentScope").asText()) && item.path("enabled").asBoolean())
                    .map(item -> objectMapper.convertValue(item, LoraModelPointResponse.class)).toList();
        } catch (Exception exception) {
            log.warn("[LORA_CONFIG_FETCH_ERROR] target=model-mappings exception={} message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw new ManagerConfigUnavailableException("model-mappings", exception);
        }
    }

    public List<LoraOverridePointResponse> findAllEnabledOverrides() {
        // Manager에서 장비별 override 테이블/API가 제거되었다.
        // 기존 LoRa 캐시 인터페이스는 유지하고 모델별 공통 MQTT 포인트만 사용한다.
        return List.of();
    }

    /**
     * MQTT 연결 단위 소스 목록. 장비 endpoint와 달리 source 하나가 여러 장비 메시지를 받는다.
     * LoRa 전용 경로가 아닌 공통 경로를 쓰므로 AGENT 소스도 함께 내려온다.
     */
    public List<LoraMqttSourceResponse> findAllSources() {
        if (!properties.isEnabled()) return List.of();
        try {
            LoraApiListResponse<LoraMqttSourceResponse> response = managerRestClient.get()
                    .uri("/api/manager/mqtt/sources/bulk")
                    .retrieve().body(new ParameterizedTypeReference<>() { });
            if (response == null || response.status() != 200 || response.data() == null) {
                throw new IllegalStateException("Invalid manager MQTT source response");
            }
            return response.data();
        } catch (Exception exception) {
            log.warn("[LORA_SOURCE_FETCH_ERROR] exception={} message={}",
                    exception.getClass().getSimpleName(), exception.getMessage());
            throw new ManagerConfigUnavailableException("sources", exception);
        }
    }

    public void reportSourceStatus(Integer sourceId, LoraMqttSourceStatusReportRequest request) {
        if (!properties.isEnabled()) return;
        try {
            managerRestClient.post().uri("/api/manager/mqtt/sources/{sourceId}/status", sourceId)
                    .body(request).retrieve().toBodilessEntity();
        } catch (Exception exception) {
            log.debug("[LORA_SOURCE_STATUS_REPORT_ERROR] sourceId={} message={}", sourceId, exception.getMessage());
        }
    }

    /** 캐시 미스 시 즉시 조회용 (TTL 만료 전 새로 등록된 장비 대응). */
    public Optional<LoraDeviceLookupResponse> resolve(LoraIdType idType, String externalId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            return mqttClient.resolve(idType.name(), externalId).filter(item -> item.enabled())
                    .map(item -> new LoraDeviceLookupResponse(item.deviceId(), item.deviceName(),
                            item.deviceModelId(), item.deviceModelName(), item.enabled()));
        } catch (Exception exception) {
            log.debug("[LORA_RESOLVE_MISS] idType={} externalId={} message={}", idType, externalId, exception.getMessage());
            return Optional.empty();
        }
    }
}
