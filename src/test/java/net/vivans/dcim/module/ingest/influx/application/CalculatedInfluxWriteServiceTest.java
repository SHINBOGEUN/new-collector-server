package net.vivans.dcim.module.ingest.influx.application;

import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.write.Point;
import net.vivans.dcim.module.ingest.influx.config.InfluxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CalculatedInfluxWriteServiceTest {
    @Test
    void writesResultAndSameTickInputsInOnePoint() {
        InfluxProperties properties = new InfluxProperties();
        properties.setEnabled(true);
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        InfluxWriteService writer = new InfluxWriteService(properties, mock(net.vivans.dcim.module.ingest.manager.infrastructure.AlarmMeasurementClient.class));
        ReflectionTestUtils.setField(writer, "writeApi", api);

        writer.writeCalculated(12, 3, 1.5, Map.of("FACILITY", 150.0, "IT", 100.0),
                Instant.parse("2026-10-01T15:00:00Z"));

        org.mockito.ArgumentCaptor<Point> capture = org.mockito.ArgumentCaptor.forClass(Point.class);
        verify(api).writePoint(capture.capture());
        String line = capture.getValue().toLineProtocol();
        assertThat(line).contains("metric_kind=calculated", "calculated_metric_id=12",
                "calculated_config_version=3", "value=1.5", "input_FACILITY=150.0", "input_IT=100.0");
        assertThat(line).doesNotContain("total_power", "cooler_power");
    }

    @Test
    void rejectsMissingInputBeforeWriting() {
        InfluxProperties properties = new InfluxProperties();
        properties.setEnabled(true);
        WriteApiBlocking api = mock(WriteApiBlocking.class);
        InfluxWriteService writer = new InfluxWriteService(properties, mock(net.vivans.dcim.module.ingest.manager.infrastructure.AlarmMeasurementClient.class));
        ReflectionTestUtils.setField(writer, "writeApi", api);

        assertThatThrownBy(() -> writer.writeCalculated(12, 1, 1.0, Map.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).writePoint(any(Point.class));
    }
}
