package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.CalculatedMetricCollectionSourceSpec;
import net.vivans.dcim.module.job.domain.CalculatedMetricCollectionSpec;
import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusByteOrder;
import net.vivans.dcim.module.job.domain.modbus.ModbusDataType;
import net.vivans.dcim.module.job.domain.modbus.ModbusRegisterType;
import net.vivans.dcim.module.modbus.ModbusQueryClient;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CalculatedTickRunnerTest {
    @Test
    void readsBothProtocolsInOneTickBeforePublishing() throws Exception {
        SnmpQueryClient snmp = mock(SnmpQueryClient.class);
        ModbusQueryClient modbus = mock(ModbusQueryClient.class);
        MqttPublisher mqtt = mock(MqttPublisher.class);
        when(snmp.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(Map.of("FACILITY", 120D));
        when(modbus.read(any(), anyList(), anyInt(), anyInt())).thenReturn(Map.of("IT", 60D));
        var point = new CollectionGroupModbusPointSpec("IT", ModbusRegisterType.HOLDING, 0,
                ModbusDataType.FLOAT32, ModbusByteOrder.ABCD, 1D, 0D);
        var spec = new CalculatedMetricCollectionSpec(1, 1, "0 */5 * * * *", "public", 1000, 0, List.of(
                new CalculatedMetricCollectionSourceSpec(7, "localhost", 161, "FACILITY", ".1.2.3", 1D,
                        "FACILITY", "snmp", null, null),
                new CalculatedMetricCollectionSourceSpec(8, "localhost", 502, "IT", null, null,
                        "IT", "modbus", 1, point)), "FACILITY / IT");
        new CalculatedMetricTickRunner(snmp, modbus, mqtt).run(spec, new AtomicBoolean());
        verify(mqtt).publishCalculatedReading(eq(1), eq(1), eq(2D), eq(Map.of("FACILITY", 120D, "IT", 60D)));
    }

    @Test
    void doesNotPublishWhenOneFreshSourceIsMissing() throws Exception {
        SnmpQueryClient snmp = mock(SnmpQueryClient.class);
        ModbusQueryClient modbus = mock(ModbusQueryClient.class);
        MqttPublisher mqtt = mock(MqttPublisher.class);
        when(snmp.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(Map.of());
        var spec = new CalculatedMetricCollectionSpec(1, 1, "0 */5 * * * *", "public", 1000, 0, List.of(
                new CalculatedMetricCollectionSourceSpec(7, "localhost", 161, "FACILITY", ".1.2.3", 1D,
                        "FACILITY", "snmp", null, null)), "FACILITY");
        AtomicReference<Boolean> success = new AtomicReference<>();
        AtomicReference<String> reason = new AtomicReference<>();
        new CalculatedMetricTickRunner(snmp, modbus, mqtt).run(spec, new AtomicBoolean(), (ok, failure) -> {
            success.set(ok);
            reason.set(failure);
        });
        verify(mqtt, never()).publishCalculatedReading(anyInt(), anyInt(), anyDouble(), anyMap());
        assertFalse(success.get());
        assertTrue(reason.get().contains("missing calculated source"));
    }
}
