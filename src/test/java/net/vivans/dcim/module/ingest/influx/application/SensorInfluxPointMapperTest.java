package net.vivans.dcim.module.ingest.influx.application;

import com.influxdb.client.write.Point;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerDeviceResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensorInfluxPointMapperTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-08-20T10:51:00Z");

    @Test
    void writesNarrowPointPerMetric() {
        ManagerDeviceResponse device = new ManagerDeviceResponse(
                9,
                10,
                "AP8959",
                "APC",
                "PDU",
                "RACK01",
                "Rack-01",
                "PDU-좌",
                "desc",
                true
        );
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("V", 219);
        values.put("W", 519.5);

        List<Point> points = SensorInfluxPointMapper.toPoints("dcim_sensor", 9, device, values, COLLECTED_AT);
        long timestamp = COLLECTED_AT.toEpochMilli();

        assertThat(points).hasSize(2);
        assertThat(points.get(0).toLineProtocol()).isEqualTo(
                "dcim_sensor,device_id=9,device_name=PDU-좌,device_type=PDU,location_code=RACK01,model_id=10,point_name=V,protocol=snmp value=219.0 "
                        + timestamp
        );
        assertThat(points.get(1).toLineProtocol()).isEqualTo(
                "dcim_sensor,device_id=9,device_name=PDU-좌,device_type=PDU,location_code=RACK01,model_id=10,point_name=W,protocol=snmp value=519.5 "
                        + timestamp
        );
    }

    @Test
    void addsComponentTagForGpuSlot() {
        List<Point> points = SensorInfluxPointMapper.toPoints(
                "dcim_sensor",
                40,
                null,
                Map.of("gpu_temperature", 72.0),
                COLLECTED_AT,
                "0"
        );

        assertThat(points).hasSize(1);
        assertThat(points.get(0).toLineProtocol()).contains("component=0");
        assertThat(points.get(0).toLineProtocol()).contains("point_name=gpu_temperature");
        assertThat(points.get(0).toLineProtocol()).contains("value=72.0");
    }

    @Test
    void omitsOptionalTagsWhenManagerLookupMissing() {
        List<Point> points = SensorInfluxPointMapper.toPoints(
                "dcim_sensor",
                9,
                null,
                Map.of("V", 220.1),
                COLLECTED_AT
        );

        assertThat(points).hasSize(1);
        assertThat(points.get(0).toLineProtocol()).contains("device_id=9");
        assertThat(points.get(0).toLineProtocol()).contains("point_name=V");
        assertThat(points.get(0).toLineProtocol()).contains("protocol=snmp");
        assertThat(points.get(0).toLineProtocol()).contains("value=220.1");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("location_code=");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("model_id=");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("device_type=");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("device_name=");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("component=");
    }

    @Test
    void usesCallerSuppliedProtocolTagForLoraPath() {
        List<Point> points = SensorInfluxPointMapper.toPoints(
                "dcim_sensor",
                9,
                null,
                Map.of("TEMPERATURE", 23.5),
                COLLECTED_AT,
                null,
                "mqtt"
        );

        assertThat(points).hasSize(1);
        assertThat(points.get(0).toLineProtocol()).contains("protocol=mqtt");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("protocol=snmp");
    }

    @Test
    void usesModbusProtocolForScheduledPoint() {
        List<Point> points = SensorInfluxPointMapper.toPoints(
                "dcim_sensor", 101, null, Map.of("POWER", 1250.5), COLLECTED_AT, null, "modbus");

        assertThat(points).hasSize(1);
        assertThat(points.get(0).toLineProtocol()).contains("device_id=101", "point_name=POWER",
                "protocol=modbus", "value=1250.5");
    }

    @Test
    void blankProtocolFallsBackToSnmp() {
        List<Point> points = SensorInfluxPointMapper.toPoints(
                "dcim_sensor",
                9,
                null,
                Map.of("V", 220.0),
                COLLECTED_AT,
                null,
                "  "
        );

        assertThat(points).hasSize(1);
        assertThat(points.get(0).toLineProtocol()).contains("protocol=snmp");
    }

    @Test
    void skipsNullAndNonNumericValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("V", 220.1);
        values.put("A", null);
        values.put("STATUS", "OK");
        values.put("", 1);
        values.put("W", Double.NaN);

        List<Point> points = SensorInfluxPointMapper.toPoints("dcim_sensor", 9, null, values, COLLECTED_AT);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).toLineProtocol()).contains("point_name=V");
        assertThat(points.get(0).toLineProtocol()).contains("value=220.1");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("point_name=A");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("point_name=STATUS");
        assertThat(points.get(0).toLineProtocol()).doesNotContain("point_name=W");
    }

    @Test
    void loraRetainsLegacyProtocolTagsAndMillisecondPrecision() {
        var time = java.time.Instant.parse("2026-10-14T08:11:13.545533Z");
        var points = SensorInfluxPointMapper.toPoints("dcim_sensor", 41, null,
                Map.of("TEMPERATURE", 23.89), time, null, "mqtt");
        assertThat(points.get(0).toLineProtocol()).contains("device_id=41", "protocol=mqtt",
                "point_name=TEMPERATURE", "value=23.89", Long.toString(time.toEpochMilli()))
                .doesNotContain("component", "scope=");
    }
}
