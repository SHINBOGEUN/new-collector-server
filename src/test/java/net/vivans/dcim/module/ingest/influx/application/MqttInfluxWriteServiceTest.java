package net.vivans.dcim.module.ingest.influx.application;

import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import net.vivans.dcim.module.ingest.influx.config.InfluxProperties;
import net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MqttInfluxWriteServiceTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void writesNarrowPointsThroughInfluxApi() {
        var properties = new InfluxProperties();
        properties.setEnabled(true);
        properties.setMeasurement("dcim_sensor");
        var service = new InfluxWriteService(properties, mock(net.vivans.dcim.module.ingest.manager.infrastructure.AlarmMeasurementClient.class));
        var writeApi = mock(WriteApiBlocking.class);
        ReflectionTestUtils.setField(service, "writeApi", writeApi);
        service.writeMqttPoints(40, 8, null, List.of(
                new MqttMeasurement("gpu_temperature", 59, "GPU", "0", "GPU-AAA")), Instant.parse("2026-10-14T08:11:13Z"));
        ArgumentCaptor<List<Point>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(writeApi).writePoints(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).toLineProtocol()).contains("device_id=40", "component_uuid=GPU-AAA", "value=59");
    }
}
