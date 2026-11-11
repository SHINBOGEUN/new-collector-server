package net.vivans.dcim.module.ingest.influx.application;

import com.influxdb.client.WriteApiBlocking;
import net.vivans.dcim.module.ingest.influx.config.InfluxProperties;
import net.vivans.dcim.module.ingest.manager.infrastructure.AlarmMeasurementClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Map;
import static org.mockito.Mockito.*;

class AlarmInfluxWriteServiceTest {
    @Test
    void notifiesManagerOnlyAfterSuccessfulInfluxWrite() {
        var properties = new InfluxProperties();
        properties.setEnabled(true);
        var alarms = mock(AlarmMeasurementClient.class);
        var influx = mock(WriteApiBlocking.class);
        var writer = new InfluxWriteService(properties, alarms);
        ReflectionTestUtils.setField(writer, "writeApi", influx);
        Instant at = Instant.now();
        writer.writeSensorPoints(34, null, Map.of("TEMP", 32.5), at, null, "mqtt");
        var order = inOrder(influx, alarms);
        order.verify(influx).writePoints(anyList());
        order.verify(alarms).afterWrite(34, "mqtt", at, Map.of("TEMP", 32.5));
    }
}
