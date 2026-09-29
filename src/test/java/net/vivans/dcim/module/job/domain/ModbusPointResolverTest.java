package net.vivans.dcim.module.job.domain;

import net.vivans.dcim.module.job.domain.modbus.ModbusRegisterType;

import net.vivans.dcim.module.job.domain.modbus.ModbusPointResolver;

import net.vivans.dcim.module.job.domain.modbus.ModbusDataType;

import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;

import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;

import net.vivans.dcim.module.job.domain.modbus.ModbusByteOrder;

import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModbusPointResolverTest {
    private final CollectionGroupModbusPointSpec common = new CollectionGroupModbusPointSpec(
            "TEMP", ModbusRegisterType.INPUT, 256, ModbusDataType.INT16, null, 0.1);
    private final CollectionGroupModbusPointSpec dedicated = new CollectionGroupModbusPointSpec(
            "TOTAL_WT", ModbusRegisterType.HOLDING, 11265, ModbusDataType.FLOAT32, ModbusByteOrder.CDAB, 1000.0);

    @Test
    void nullTargetPointsUsesGroupPoints() {
        assertThat(resolve(List.of(common), null)).containsExactly(common);
    }

    @Test
    void targetPointsReplaceGroupPointsWithoutMerging() {
        assertThat(resolve(List.of(common), List.of(dedicated))).containsExactly(dedicated);
    }

    @Test
    void targetPointsWorkWithoutGroupPoints() {
        assertThat(resolve(null, List.of(dedicated))).containsExactly(dedicated);
    }

    @Test
    void emptyTargetPointsDoesNotFallBackToGroup() {
        assertThat(resolve(List.of(common), List.of())).isEmpty();
    }

    @Test
    void absentOrEmptyGroupAndAbsentTargetPointsReturnsEmpty() {
        assertThat(resolve(null, null)).isEmpty();
        assertThat(resolve(List.of(), null)).isEmpty();
    }

    private List<CollectionGroupModbusPointSpec> resolve(
            List<CollectionGroupModbusPointSpec> groupPoints,
            List<CollectionGroupModbusPointSpec> targetPoints
    ) {
        var target = new ModbusCollectionTargetSpec(17, "host", 502, 0, targetPoints);
        var spec = new ModbusCollectionGroupSpec(1, 2, 3, "modbus", "0 * * * * *",
                2000, 1, 10, groupPoints, List.of(target), List.of());
        return ModbusPointResolver.resolve(spec, target);
    }
}
