package net.vivans.dcim.module.ingest.mqtt.domain;

import java.util.Map;
import java.util.Locale;

public record SensorMqttPayload(
        String datetime,
        Map<String, Map<String, Object>> data,
        String type,
        String protocol
) {
    /** 과거 정기 수집 메시지에는 protocol 필드가 없으므로 SNMP로만 기본 처리한다. */
    public String resolvedProtocol() {
        if (protocol == null) {
            return "snmp";
        }
        String normalized = protocol.trim().toLowerCase(Locale.ROOT);
        if (!"snmp".equals(normalized) && !"modbus".equals(normalized)) {
            throw new IllegalArgumentException("unsupported sensor protocol: " + protocol);
        }
        return normalized;
    }
}
