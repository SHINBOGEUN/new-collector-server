package net.vivans.dcim.module.ingest.influx.application;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.influx.config.InfluxProperties;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerDeviceResponse;
import net.vivans.dcim.module.ingest.manager.infrastructure.AlarmMeasurementClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfluxWriteService {

    private final InfluxProperties properties;
    private final AlarmMeasurementClient alarmClient;
    private InfluxDBClient client;
    private WriteApiBlocking writeApi;

    public void writeMqttPoints(int deviceId, Integer modelId, ManagerDeviceResponse device,
            List<net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement> values, Instant collectedAt) {
        if (!properties.isEnabled() || writeApi == null || values.isEmpty()) return;
        var points = MqttInfluxPointMapper.toPoints(properties.getMeasurement(), deviceId, modelId, device, values, collectedAt);
        if (!points.isEmpty()) writeApi.writePoints(points);
        Map<String, Double> thresholdValues = values.stream()
                .filter(value -> value.componentType() == null || value.componentType().isBlank())
                .filter(value -> Double.isFinite(value.value()))
                .collect(Collectors.toMap(net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement::pointName,
                        net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement::value, (first, later) -> later));
        if (!points.isEmpty() && !thresholdValues.isEmpty()) {
            alarmClient.afterWrite(deviceId, "mqtt", collectedAt, thresholdValues);
        }
        log.info("MQTT Influx write deviceId={} pointCount={}", deviceId, points.size());
    }

    @PostConstruct
    public void connect() {
        if (!properties.isEnabled()) {
            log.info("InfluxDB write disabled");
            return;
        }
        client = InfluxDBClientFactory.create(
                properties.getUrl(),
                properties.getToken().toCharArray(),
                properties.getOrg(),
                properties.getBucket()
        );
        writeApi = client.getWriteApiBlocking();
        log.info("InfluxDB connected: url={} org={} bucket={}", properties.getUrl(), properties.getOrg(), properties.getBucket());
    }

    public void writeSensorPoints(
            int deviceId,
            ManagerDeviceResponse device,
            Map<String, Object> values,
            Instant collectedAt
    ) {
        writeSensorPoints(deviceId, device, values, collectedAt, null, null);
    }

    /**
     * protocol/component을 호출 측에서 지정할 수 있는 오버로드.
     * LoRa/MQTT 수집 경로는 protocol="mqtt" 로 호출한다. 기존 호출부는 위 4-arg 오버로드를 그대로 사용하며
     * 내부적으로 protocol=null(=snmp로 처리)로 위임되므로 동작 변화가 없다.
     */
    public void writeSensorPoints(
            int deviceId,
            ManagerDeviceResponse device,
            Map<String, Object> values,
            Instant collectedAt,
            String component,
            String protocol
    ) {
        if (!properties.isEnabled() || writeApi == null) {
            log.debug("Influx write skipped (disabled) deviceId={}", deviceId);
            return;
        }
        List<Point> points = SensorInfluxPointMapper.toPoints(
                properties.getMeasurement(),
                deviceId,
                device,
                values,
                collectedAt,
                component,
                protocol == null || protocol.isBlank() ? "snmp" : protocol
        );
        if (points.isEmpty()) {
            log.warn("Influx write skipped (no valid points) deviceId={}", deviceId);
            return;
        }
        writeApi.writePoints(points);
        if (component == null || component.isBlank()) {
            Map<String, Double> thresholdValues = values.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                            && entry.getKey().chars().noneMatch(ch -> ch == ' ' || ch == ',' || ch == '=' || ch == '"'))
                    .map(entry -> new java.util.AbstractMap.SimpleImmutableEntry<>(entry.getKey(), toDoubleOrNull(entry.getValue())))
                    .filter(entry -> entry.getValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (!thresholdValues.isEmpty()) {
                alarmClient.afterWrite(deviceId, protocol == null || protocol.isBlank() ? "snmp" : protocol,
                        collectedAt == null ? Instant.now() : collectedAt, thresholdValues);
            }
        }
        log.info("Influx write deviceId={} protocol={} {}", deviceId, protocol, formatKeyValues(values));
    }


    public void writeCalculated(Integer definitionId, Integer configVersion, Double value,
                                Map<String, Double> inputs, Instant collectedAt) {
        if (!properties.isEnabled() || writeApi == null) return;
        if (definitionId == null || value == null || !Double.isFinite(value)
                || inputs == null || inputs.isEmpty() || inputs.values().stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
            throw new IllegalArgumentException("invalid calculated metric payload");
        }
        Point point = Point.measurement(properties.getMeasurement())
                .addTag("metric_kind", "calculated")
                .addTag("calculated_metric_id", String.valueOf(definitionId))
                .addTag("calculated_config_version", String.valueOf(configVersion == null ? 1 : configVersion))
                .addTag("point_name", "CALCULATED")
                .addTag("protocol", "derived")
                .addField("value", value)
                .time(collectedAt == null ? Instant.now() : collectedAt, com.influxdb.client.domain.WritePrecision.MS);
        for (Map.Entry<String, Double> input : inputs.entrySet()) {
            if (input.getKey() == null || !input.getKey().matches("[A-Za-z][A-Za-z0-9_]{0,31}")) {
                throw new IllegalArgumentException("invalid calculated source alias");
            }
            point.addField("input_" + input.getKey(), input.getValue());
        }
        writeApi.writePoint(point);
        log.info("[CALCULATED_INFLUX_END] definitionId={} version={} sourceCount={}",
                definitionId, configVersion, inputs.size());
    }

    /** 실제 적재되는 point만 `key=value` 형태로 연결 */
    private static String formatKeyValues(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank())
                .map(e -> {
                    Double value = toDoubleOrNull(e.getValue());
                    return value == null ? null : e.getKey() + "=" + value;
                })
                .filter(s -> s != null)
                .collect(Collectors.joining(" "));
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

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
