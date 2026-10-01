package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusBitFieldSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusByteOrder;
import net.vivans.dcim.module.job.domain.modbus.ModbusDataType;
import net.vivans.dcim.module.job.domain.modbus.ModbusRegisterType;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModbusSpecValidatorTest {
    private final ModbusSpecValidator validator = new ModbusSpecValidator();

    @Test
    void acceptsRdcCommonPointsForDifferentDevices() {
        var points = List.of(point("TEMP", 0, ModbusDataType.INT16, null));
        accepts(spec(points, List.of(target(21, 0, null), target(23, 247, null))));
    }

    @Test
    void acceptsAccuraRepeatedDestinationWithDifferentNames() {
        accepts(spec(null, List.of(
                target(17, 0, List.of(point("TOTAL_WT", 100, ModbusDataType.FLOAT32, ModbusByteOrder.ABCD))),
                target(17, 3, List.of(point("WC-FAN-TOTAL_WT", 200, ModbusDataType.FLOAT32, ModbusByteOrder.CDAB))),
                target(20, 1, List.of(point("TOTAL_WT", 300, ModbusDataType.FLOAT32, ModbusByteOrder.ABCD))))));
    }

    @Test
    void rejectsSameDestinationAndNameAcrossUnitsOrWithinTarget() {
        var points = List.of(point("POWER", 0, ModbusDataType.INT16, null));
        rejects(spec(points, List.of(target(17, 0, null), target(17, 3, null))), "duplicate");
        rejects(spec(List.of(points.get(0), points.get(0)), List.of(target(17, 0, null))), "duplicate");
    }

    @Test
    void dedicatedPointsReplaceCommonAndEmptyPointsDoNotFallBack() {
        var invalidCommon = List.of(point("BAD", -1, ModbusDataType.INT16, null));
        accepts(spec(invalidCommon, List.of(target(17, 1,
                List.of(point("GOOD", 0, ModbusDataType.INT16, null))))));
        accepts(spec(invalidCommon, List.of(target(17, 1, List.of()))));
        accepts(spec(null, List.of(target(17, 1, null))));
        accepts(spec(null, null));
    }

    @Test
    void checksAddressBoundaryAgainstRegisterWidth() {
        checkPoint(point("P", 65535, ModbusDataType.INT16, null), true);
        checkPoint(point("P", 65534, ModbusDataType.FLOAT32, ModbusByteOrder.ABCD), true);
        checkPoint(point("P", 65535, ModbusDataType.FLOAT32, ModbusByteOrder.ABCD), false);
        for (Integer address : new Integer[] {null, -1, 65536}) {
            checkPoint(point("P", address, ModbusDataType.INT16, null), false);
        }
    }

    @Test
    void checksAllDataTypeAndByteOrderCombinations() {
        for (var type : ModbusDataType.values()) {
            checkPoint(point("P", 0, type, null), !type.isMultiRegister());
            for (var order : ModbusByteOrder.values()) {
                boolean valid = type.isMultiRegister() != order.isSingleRegisterOrder();
                checkPoint(point("P", 0, type, order), valid);
            }
        }
    }

    @Test
    void bitReadsUseSingleAddressAndDoNotRequireRegisterByteOrder() {
        for (var registerType : List.of(ModbusRegisterType.COIL, ModbusRegisterType.DISCRETE)) {
            checkPoint(new CollectionGroupModbusPointSpec("BIT", registerType, 65535,
                    ModbusDataType.FLOAT32, null, null), true);
        }
    }

    @Test
    void validatesDerivedBitRangesAndNames() {
        var valid = new CollectionGroupModbusPointSpec("RAW", ModbusRegisterType.HOLDING, 0,
                ModbusDataType.UINT16, null, null, null,
                List.of(new ModbusBitFieldSpec("VALVE", 8, 2, Map.of("2", 1L), -1L)));
        checkPoint(valid, true);
        var invalidRange = new CollectionGroupModbusPointSpec("RAW", ModbusRegisterType.HOLDING, 0,
                ModbusDataType.UINT16, null, null, null,
                List.of(new ModbusBitFieldSpec("VALVE", 15, 2, Map.of(), null)));
        checkPoint(invalidRange, false);
        var duplicateName = new CollectionGroupModbusPointSpec("RAW", ModbusRegisterType.HOLDING, 0,
                ModbusDataType.UINT16, null, null, null,
                List.of(new ModbusBitFieldSpec("RAW", 0, 1, Map.of(), null)));
        checkPoint(duplicateName, false);
    }

    @Test
    void rejectsInvalidTargetFields() {
        for (var target : new ModbusCollectionTargetSpec[] {null,
                target(1, null, null), target(1, -1, null), target(1, 248, null),
                target(null, 1, null), target(0, 1, null),
                new ModbusCollectionTargetSpec(1, " ", 502, 1, null),
                new ModbusCollectionTargetSpec(1, "host", 0, 1, null),
                new ModbusCollectionTargetSpec(1, "host", 65536, 1, null)}) {
            rejects(spec(List.of(), Collections.singletonList(target)), "targets[0]");
        }
    }

    @Test
    void rejectsMissingPointFieldsAndNonFiniteScales() {
        checkPoint(null, false);
        checkPoint(new CollectionGroupModbusPointSpec("P", null, 0, ModbusDataType.INT16, null, null), false);
        checkPoint(new CollectionGroupModbusPointSpec("P", ModbusRegisterType.INPUT, 0, null, null, null), false);
        for (double scale : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            checkPoint(new CollectionGroupModbusPointSpec("P", ModbusRegisterType.INPUT, 0,
                    ModbusDataType.INT16, null, scale), false);
        }
    }

    @Test
    void rejectsInvalidNames() {
        for (String name : new String[] {null, "", "A B", "A,B", "A=B", "A\"B", "A\tB"}) {
            checkPoint(point(name, 0, ModbusDataType.INT16, null), false);
        }
    }

    private CollectionGroupModbusPointSpec point(String name, Integer address,
                                                 ModbusDataType type, ModbusByteOrder order) {
        return new CollectionGroupModbusPointSpec(name, ModbusRegisterType.INPUT, address, type, order, null);
    }

    private ModbusCollectionTargetSpec target(Integer device, Integer unit,
                                               List<CollectionGroupModbusPointSpec> points) {
        return new ModbusCollectionTargetSpec(device, "localhost", 502, unit, points);
    }

    private ModbusCollectionGroupSpec spec(List<CollectionGroupModbusPointSpec> points,
                                          List<ModbusCollectionTargetSpec> targets) {
        return new ModbusCollectionGroupSpec(1, 2, 3, "modbus", "0 * * * * *",
                2000, 1, 10, points, targets, List.of());
    }

    private void checkPoint(CollectionGroupModbusPointSpec point, boolean valid) {
        var spec = spec(Collections.singletonList(point), List.of(target(1, 1, null)));
        if (valid) {
            accepts(spec);
        } else {
            rejects(spec, "effectivePoints[0]");
        }
    }

    private void accepts(ModbusCollectionGroupSpec spec) {
        assertThatCode(() -> validator.validate(spec)).doesNotThrowAnyException();
    }

    private void rejects(ModbusCollectionGroupSpec spec, String message) {
        assertThatThrownBy(() -> validator.validate(spec))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
}
