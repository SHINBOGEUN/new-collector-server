package net.vivans.dcim.module.modbus;

import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusByteOrder;
import net.vivans.dcim.module.job.domain.modbus.ModbusDataType;
import net.vivans.dcim.module.job.domain.modbus.ModbusRegisterType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class ModbusValueDecoderTest {

    @Test
    void decodesSignedUnsignedAndScaledValues() throws Exception {
        assertThat(ModbusValueDecoder.decode(point(ModbusDataType.INT16, null, 1.0),
                new byte[]{(byte) 0xff, (byte) 0x9c}).doubleValue()).isEqualTo(-100.0);
        assertThat(ModbusValueDecoder.decode(point(ModbusDataType.UINT16, null, 0.1),
                new byte[]{0, 100}).doubleValue()).isEqualTo(10.0);
        assertThat(ModbusValueDecoder.decode(point(ModbusDataType.UINT32, ModbusByteOrder.ABCD, null),
                new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}).doubleValue())
                .isEqualTo(4294967295.0);
    }

    @Test
    void decodesFloatWithSwappedWords() throws Exception {
        assertThat(ModbusValueDecoder.decode(point(ModbusDataType.FLOAT32, ModbusByteOrder.CDAB, null),
                new byte[]{0, 0, 0x42, 0x2a}).doubleValue()).isEqualTo(42.5);
    }

    @Test
    void appliesOffsetAfterScaleForChillerTemperature() throws Exception {
        var temperature = new CollectionGroupModbusPointSpec("IN-TEMP", ModbusRegisterType.INPUT, 0,
                ModbusDataType.UINT16, null, 0.1, -50.0);
        assertThat(ModbusValueDecoder.decode(temperature, new byte[]{0x02, (byte) 0xfe}).doubleValue())
                .isCloseTo(26.6, offset(1e-9));
    }

    @Test
    void decodesBitAndRejectsNonFiniteFloat() throws Exception {
        var coil = new CollectionGroupModbusPointSpec("RUN", ModbusRegisterType.COIL, 0,
                ModbusDataType.UINT16, null, null);
        assertThat(ModbusValueDecoder.decode(coil, new byte[]{1}).intValue()).isEqualTo(1);
        assertThat(ModbusValueDecoder.decode(coil, new byte[]{0}).intValue()).isEqualTo(0);
        assertThatThrownBy(() -> ModbusValueDecoder.decode(point(ModbusDataType.FLOAT32,
                ModbusByteOrder.ABCD, null), new byte[]{0x7f, (byte) 0xc0, 0, 0}))
                .isInstanceOf(java.io.IOException.class);
    }

    private static CollectionGroupModbusPointSpec point(ModbusDataType dataType,
                                                         ModbusByteOrder byteOrder, Double scale) {
        return new CollectionGroupModbusPointSpec("VALUE", ModbusRegisterType.HOLDING, 100,
                dataType, byteOrder, scale);
    }
}
