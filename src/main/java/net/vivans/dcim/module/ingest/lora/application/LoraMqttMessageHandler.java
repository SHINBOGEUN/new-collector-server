package net.vivans.dcim.module.ingest.lora.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.influx.application.InfluxWriteService;
import net.vivans.dcim.module.ingest.lora.domain.LoraIdType;
import net.vivans.dcim.module.ingest.lora.domain.LoraPayloadPathResolver;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerDeviceClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerDeviceResponse;
import net.vivans.dcim.module.ingest.mqtt.config.LoraMqttProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Dragino/LoRa(ChirpStack 등 LoRaWAN 네트워크 서버) MQTT uplink 처리.
 *
 * 식별 우선순위(레거시 운영 호환 포함):
 *   1) payload에 devEUI가 있으면 devEUI로 매칭
 *   2) devEUI가 없으면 deviceName으로 매칭 (레거시 DraginoDataService와 동일한 방식)
 *   3) 둘 다 없으면 로그만 남기고 종료, 식별자는 있지만 등록된 device와 매칭되지 않으면 DEBUG 로그만 남기고 무시
 *
 * 필드 매핑은 device model 기본 매핑을 사용한다. 매핑에 없는 payload_field는
 * "이 장비에서 관리하지 않는 필드"로 보고 조용히 건너뛴다(오류 아님). 매핑은 있는데 값 해석에 실패한
 * 경우만 오류로 취급한다. sentinel(327.67/409.5 등 "정상적으로 값 없음")은 오류가 아니다.
 *
 * 영구 오류 이력(lora_ingest_error_log) 대신, 등록된 장비별 최근 상태만 {@link LoraRuntimeStatusCache}에
 * 메모리로 보관한다. 미등록 장비와 식별 자체에 실패한 메시지는 device로 귀속시킬 수 없으므로 캐시에
 * 반영하지 않고 로그만 남긴다. raw payload는 어떤 경우에도 저장하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoraMqttMessageHandler {

    private final ObjectMapper objectMapper;
    private final LoraMqttProperties properties;
    private final LoraConfigCache configCache;
    private final LoraValueConverter valueConverter;
    private final ManagerDeviceClient managerDeviceClient;
    private final LoraRuntimeStatusCache runtimeStatusCache;
    private final InfluxWriteService influxWriteService;

    public void handle(String topic, byte[] payload) {
        long startedAt = System.nanoTime();
        Instant receivedAt = Instant.now();

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception exception) {
            log.warn("[LORA_MQTT_PARSE_ERROR] topic={} exception={} message={}",
                    topic, exception.getClass().getSimpleName(), exception.getMessage());
            return;
        }

        IdentifiedExternalId identified = extractExternalId(root);
        if (identified == null) {
            log.warn("[LORA_MQTT_NO_IDENTIFIER] topic={}", topic);
            return;
        }

        Optional<LoraResolvedDevice> resolved = configCache.resolveDevice(identified.idType(), identified.externalId());
        if (resolved.isEmpty()) {
            log.debug("[LORA_MQTT_IGNORED_DEVICE] reason=UNREGISTERED_DEVICE idType={} externalId={} topic={}",
                    identified.idType(), identified.externalId(), topic);
            return;
        }

        LoraResolvedDevice device = resolved.get();
        runtimeStatusCache.recordReceived(device.deviceId(), receivedAt);

        Set<String> fields = configCache.modelFieldsOf(device.deviceModelId());
        if (fields.isEmpty()) {
            log.warn("[LORA_MQTT_NO_MAPPING] deviceId={} deviceModelId={} topic={}",
                    device.deviceId(), device.deviceModelId(), topic);
            runtimeStatusCache.recordError(device.deviceId(), "NO_MAPPING", "모델 매핑 없음", receivedAt);
            return;
        }

        Map<String, Object> values = new HashMap<>();
        int failedCount = 0;
        for (String payloadField : fields) {
            Optional<LoraMappingRule> mapping = configCache.resolveMapping(device.deviceModelId(), payloadField);
            if (mapping.isEmpty()) {
                continue;
            }
            JsonNode rawValue = LoraPayloadPathResolver.resolve(root, payloadField);
            if (rawValue == null) {
                // 이번 메시지에 해당 필드가 포함되지 않음 - Dragino 장비마다 매 전송 시 모든 필드를 담지 않을 수 있어 정상 상황.
                continue;
            }
            LoraConversionResult result = valueConverter.convert(rawValue, mapping.get().scale(), mapping.get().valueMap());
            switch (result.status()) {
                case OK -> values.put(mapping.get().pointName(), result.value());
                case NO_DATA -> log.debug("lora field no-data deviceId={} field={}", device.deviceId(), payloadField);
                case FAILED -> {
                    failedCount++;
                    log.warn("[LORA_MQTT_FIELD_CONVERT_FAILED] deviceId={} field={} rawValue={}",
                            device.deviceId(), payloadField, rawValue);
                }
            }
        }

        boolean hadFieldError = failedCount > 0;
        if (hadFieldError) {
            runtimeStatusCache.recordError(device.deviceId(), "FIELD_CONVERSION_FAILED",
                    "변환 실패 필드 수=" + failedCount, receivedAt);
        }

        if (values.isEmpty()) {
            log.debug("[LORA_MQTT_NO_VALID_VALUES] deviceId={} topic={} elapsedMs={}",
                    device.deviceId(), topic, elapsedMillis(startedAt));
            return;
        }

        ManagerDeviceResponse managerDevice = managerDeviceClient.findDevice(device.deviceId()).orElse(null);
        try {
            influxWriteService.writeSensorPoints(device.deviceId(), managerDevice, values, receivedAt, null, "mqtt");
        } catch (RuntimeException exception) {
            log.warn("[LORA_MQTT_INFLUX_WRITE_FAILED] deviceId={} idType={} externalId={} exception={} message={}",
                    device.deviceId(), identified.idType(), identified.externalId(),
                    exception.getClass().getSimpleName(), exception.getMessage());
            runtimeStatusCache.recordError(device.deviceId(), "INFLUX_WRITE_FAILED", exception.getMessage(), receivedAt);
            // 기존 MQTT source errorCount/재연결 판단 흐름이 유지되도록 예외를 상위(LoraMqttSubscriber)까지 다시 전파한다.
            throw exception;
        }

        // hadFieldError=true인 경우 방금 기록한 FIELD_CONVERSION_FAILED를 저장 성공으로 덮어써 지우지 않는다.
        runtimeStatusCache.recordSaved(device.deviceId(), receivedAt, values.size(), !hadFieldError);
        log.info("[LORA_MQTT_RECEIVE_END] deviceId={} idType={} externalId={} pointCount={} topic={} elapsedMs={}",
                device.deviceId(), identified.idType(), identified.externalId(), values.size(), topic, elapsedMillis(startedAt));
    }

    private IdentifiedExternalId extractExternalId(JsonNode root) {
        JsonNode devEui = LoraPayloadPathResolver.resolve(root, properties.getDevEuiPath());
        if (devEui != null && devEui.isTextual() && !devEui.asText().isBlank()) {
            return new IdentifiedExternalId(LoraIdType.DEV_EUI, devEui.asText());
        }
        JsonNode deviceName = LoraPayloadPathResolver.resolve(root, properties.getDeviceNamePath());
        if (deviceName != null && deviceName.isTextual() && !deviceName.asText().isBlank()) {
            return new IdentifiedExternalId(LoraIdType.DEVICE_NAME, deviceName.asText());
        }
        return null;
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private record IdentifiedExternalId(LoraIdType idType, String externalId) {
    }
}
