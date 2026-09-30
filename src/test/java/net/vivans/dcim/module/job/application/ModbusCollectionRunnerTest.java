package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusDataType;
import net.vivans.dcim.module.job.domain.modbus.ModbusRegisterType;
import net.vivans.dcim.module.modbus.ModbusQueryClient;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ModbusCollectionRunnerTest {

    private final ModbusQueryClient client = mock(ModbusQueryClient.class);
    private final MqttPublisher mqtt = mock(MqttPublisher.class);
    private final CollectionMetrics metrics = mock(CollectionMetrics.class);
    private final ModbusCollectionRunner runner = new ModbusCollectionRunner(client, mqtt, metrics);
    private final CollectionGroupModbusPointSpec point = new CollectionGroupModbusPointSpec(
            "POWER", ModbusRegisterType.HOLDING, 100, ModbusDataType.UINT16, null, 1.0);
    private final ModbusCollectionTargetSpec target = new ModbusCollectionTargetSpec(
            101, "host", 502, 2, List.of(point));
    private final ModbusCollectionGroupSpec spec = new ModbusCollectionGroupSpec(
            1, 6, 10, "modbus", "0 * * * * *", 2000, 1, 10,
            List.of(), List.of(target), List.of());

    @Test
    void publishesUnderStorageDeviceIdWithModbusProtocol() throws Exception {
        when(client.read(target, List.of(point), 2000, 1)).thenReturn(Map.of("POWER", 1250.0));

        assertThat(runner.collectTarget(spec, target).success()).isTrue();
        verify(mqtt).publishSensorReading(1, 6, 101, Map.of("POWER", 1250.0), "modbus");
        verify(metrics).recordSuccess();
    }

    @Test
    void readFailureDoesNotPublishAndReportsTarget() throws Exception {
        when(client.read(target, List.of(point), 2000, 1)).thenThrow(new IOException("timeout"));

        CollectionTargetResult result = runner.collectTarget(spec, target);

        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("deviceId=101", "unitId=2", "timeout");
        verifyNoInteractions(mqtt);
        verify(metrics).recordFailure();
    }
}
