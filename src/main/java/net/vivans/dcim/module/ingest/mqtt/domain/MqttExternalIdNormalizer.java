package net.vivans.dcim.module.ingest.mqtt.domain;

import java.util.Locale;
import java.util.UUID;

/**
 * 수신 식별값 정규화. LoRa·에이전트 경로가 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * Manager의 net.vivans.dcim.module.ingest.mqtt.domain.model.MqttExternalIdNormalizer와
 * 결과가 반드시 같아야 한다. 등록(Manager)과 수신 조회(Sensor Data)의 규칙이 어긋나면
 * 등록된 장비가 있어도 매칭에 실패하고, 원인이 로그에 드러나지 않는다.
 */
public final class MqttExternalIdNormalizer {

    private MqttExternalIdNormalizer() {
    }

    public static String normalize(String identityType, String value) {
        if (identityType == null || identityType.isBlank()) {
            throw new IllegalArgumentException("identityType is required");
        }
        if (value == null || value.isBlank() || value.trim().length() > 255) {
            throw new IllegalArgumentException("externalId must contain 1 to 255 characters");
        }
        String trimmed = value.trim();
        String normalized = switch (identityType) {
            // MAC/DEV_EUI/DEVICE_NAME은 기존 LoRa 규칙(대문자 통일, 구분자 제거)을 그대로 쓴다.
            case "MAC", "DEV_EUI", "DEVICE_NAME" -> trimmed.toUpperCase(Locale.ROOT)
                    .replace(":", "").replace("-", "").replace("_", "").replace(" ", "");
            case "UUID" -> UUID.fromString(trimmed).toString();
            case "SERIAL" -> trimmed;
            default -> throw new IllegalArgumentException("Unsupported MQTT identity type: " + identityType);
        };
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("normalized externalId must contain 1 to 255 characters");
        }
        if ("MAC".equals(identityType) && !normalized.matches("[0-9A-F]{12}")) {
            throw new IllegalArgumentException("MAC must contain 12 hexadecimal digits");
        }
        return normalized;
    }
}
