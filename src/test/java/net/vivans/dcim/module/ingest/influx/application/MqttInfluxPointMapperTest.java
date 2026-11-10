package net.vivans.dcim.module.ingest.influx.application;

import java.time.Instant;
import java.util.List;
import net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MqttInfluxPointMapperTest {
    @Test
    void deviceAndGpuUseSameMeasurementAndStableDeviceId() {
        var time = Instant.parse("2026-10-14T08:11:13.545533Z");
        var lines = MqttInfluxPointMapper.toPoints("dcim_sensor", 40, 8, null, List.of(
                new MqttMeasurement("load_avg_1min", 0.01, null, null, null),
                new MqttMeasurement("gpu_temperature", 59, "GPU", "0", "GPU-AAA")), time);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).toLineProtocol()).startsWith("dcim_sensor,")
                .contains("device_id=40", "model_id=8", "protocol=mqtt", "point_name=load_avg_1min", "value=0.01")
                .doesNotContain("component_", "mac", "scope=");
        assertThat(lines.get(1).toLineProtocol()).contains("device_id=40", "point_name=gpu_temperature",
                "component_type=GPU", "component_index=0", "component_uuid=GPU-AAA", "value=59",
                Long.toString(time.getEpochSecond() * 1000000000L + time.getNano()));
    }
}
