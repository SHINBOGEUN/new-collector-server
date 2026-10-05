package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.LiveModbusTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MixedLiveCollectionTickTest {

    @Test
    void dispatchesSnmpAndModbusInSameLiveSession() throws Exception {
        SnmpCollectionRunner snmp = mock(SnmpCollectionRunner.class);
        ModbusCollectionRunner modbus = mock(ModbusCollectionRunner.class);
        CountDownLatch completed = new CountDownLatch(2);
        when(snmp.collectLiveTarget(any(), any())).thenAnswer(invocation -> {
            completed.countDown();
            return true;
        });
        when(modbus.collectLiveTarget(any(), any())).thenAnswer(invocation -> {
            completed.countDown();
            return true;
        });
        var snmpTarget = new LiveCollectionTargetSpec(7, "PDU", "127.0.0.1", 161, null, List.of());
        var modbusTarget = new LiveModbusTargetSpec("CHILLER", 17,
                new ModbusCollectionTargetSpec(31, "127.0.0.1", 502, 1, List.of()), Map.of());
        var spec = new LiveCollectionSpec(1000, "mixed", "public", 800, 0, 10,
                List.of(snmpTarget), List.of(modbusTarget));

        new CollectionTickRunner(snmp, modbus).runLive(spec, new AtomicBoolean());

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
    }
}
