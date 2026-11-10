package net.vivans.dcim.module.ingest.influx.application;

import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerDeviceResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 범용 narrow 스키마. 장비·모델이 달라도 measurement/tag/field 구조는 같다.
 * GPU처럼 장비 안 슬롯이 있으면 {@code component} tag만 추가한다.
 */
final class SensorInfluxPointMapper {

    static final String PROTOCOL_SNMP = "snmp";

    private SensorInfluxPointMapper() {
    }

    static List<Point> toPoints(
            String measurement,
            int deviceId,
            ManagerDeviceResponse device,
            Map<String, Object> values,
            Instant collectedAt
    ) {
        return toPoints(measurement, deviceId, device, values, collectedAt, null, PROTOCOL_SNMP);
    }

    static List<Point> toPoints(
            String measurement,
            int deviceId,
            ManagerDeviceResponse device,
            Map<String, Object> values,
            Instant collectedAt,
            String component
    ) {
        return toPoints(measurement, deviceId, device, values, collectedAt, component, PROTOCOL_SNMP);
    }

    /** protocol을 호출 측에서 지정한다 (예: LoRa/MQTT 수집 경로는 "mqtt"). 기존 두 오버로드는 전부 snmp로 고정 호출한다. */
    static List<Point> toPoints(
            String measurement,
            int deviceId,
            ManagerDeviceResponse device,
            Map<String, Object> values,
            Instant collectedAt,
            String component,
            String protocol
    ) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Instant time = collectedAt == null ? Instant.now() : collectedAt;
        String resolvedProtocol = (protocol == null || protocol.isBlank()) ? PROTOCOL_SNMP : protocol;
        List<Point> points = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!isValidTagValue(entry.getKey())) {
                continue;
            }
            Double value = toDoubleOrNull(entry.getValue());
            if (value == null) {
                continue;
            }
            Point point = Point.measurement(measurement)
                    .addTag("device_id", String.valueOf(deviceId))
                    .addTag("point_name", entry.getKey())
                    .addTag("protocol", resolvedProtocol)
                    .addField("value", value)
                    .time(time, WritePrecision.MS);
            if (hasText(component)) {
                point.addTag("component", component);
            }
            if (device != null && hasText(device.name())) {
                point.addTag("device_name", device.name());
            }
            if (device != null && device.modelId() != null) {
                point.addTag("model_id", String.valueOf(device.modelId()));
            }
            if (device != null && hasText(device.deviceTypeCode())) {
                point.addTag("device_type", device.deviceTypeCode());
            }
            if (device != null && hasText(device.locationNodeCode())) {
                point.addTag("location_code", device.locationNodeCode());
            }
            points.add(point);
        }
        return points;
    }

    private static boolean isValidTagValue(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch == ' ' || ch == ',' || ch == '=' || ch == '"') {
                return false;
            }
        }
        return true;
    }

    private static Double toDoubleOrNull(Object value) {
        if (value instanceof Number number) {
            double converted = number.doubleValue();
            return Double.isFinite(converted) ? converted : null;
        }
        if (value == null) {
            return null;
        }
        try {
            double converted = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(converted) ? converted : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
