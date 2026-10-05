package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveModbusTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusDataType;
import net.vivans.dcim.module.job.domain.modbus.ModbusRegisterType;
import net.vivans.dcim.module.modbus.ModbusQueryClient;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModbusLiveCollectionRunnerTest {

    @Test
    void publishesSelectedDerivedValueOnlyToLiveTopic() throws Exception {
        ModbusQueryClient query = mock(ModbusQueryClient.class);
        MqttPublisher publisher = mock(MqttPublisher.class);
        CollectionMetrics metrics = mock(CollectionMetrics.class);
        var point = new CollectionGroupModbusPointSpec("VALVE_RAW", ModbusRegisterType.HOLDING,
                0, ModbusDataType.UINT16, null, null, null, List.of());
        var target = new ModbusCollectionTargetSpec(31, "127.0.0.1", 502, 1, List.of(point));
        var liveTarget = new LiveModbusTargetSpec("CHILLER", 17, target, Map.of("VALVE_1", ""));
        var spec = new LiveCollectionSpec(1000, "modbus", "public", 800, 0, 10,
                List.of(), List.of(liveTarget));
        when(query.read(target, List.of(point), 800, 0)).thenReturn(Map.of("VALVE_RAW", 602, "VALVE_1", 1));

        boolean result = new ModbusCollectionRunner(query, publisher, metrics).collectLiveTarget(spec, liveTarget);

        assertThat(result).isTrue();
        verify(publisher).publishLivePoint(31, "CHILLER", "VALVE_1", "", 1, "modbus", 17);
        verify(publisher, never()).publishSensorReading(0, 0, 31, Map.of("VALVE_1", 1));
    }
}
