package net.vivans.dcim.module.modbus;

import net.vivans.dcim.module.job.domain.modbus.CollectionGroupModbusPointSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusByteOrder;

import java.io.IOException;
import java.math.BigDecimal;

/** Modbus 응답 바이트를 point 설정에 따라 숫자로 변환한다. */
public final class ModbusValueDecoder {

    private ModbusValueDecoder() {
    }

    public static Number decode(CollectionGroupModbusPointSpec point, byte[] bytes) throws IOException {
        if (point.registerType().isBitType()) {
            if (bytes.length != 1) {
                throw new IOException("invalid bit response length: " + bytes.length);
            }
            return bytes[0] & 1;
        }

        int count = point.dataType().getRegisterCount() * 2;
        if (bytes.length != count) {
            throw new IOException("invalid register response length: " + bytes.length);
        }
        byte[] ordered = reorder(bytes, point.effectiveByteOrder());
        long raw = 0;
        for (byte value : ordered) {
            raw = (raw << 8) | (value & 0xffL);
        }
        double decoded = switch (point.dataType()) {
            case INT16 -> (double) (short) raw;
            case UINT16 -> (double) raw;
            case INT32 -> (double) (int) raw;
            case UINT32 -> (double) raw;
            case FLOAT32 -> (double) Float.intBitsToFloat((int) raw);
        };
        if (!Double.isFinite(decoded)) {
            throw new IOException("non-finite Modbus value: " + point.name());
        }
        // Use decimal arithmetic for configured scale/offset so binary floating-point
        // artifacts (for example 23.500000000000004) do not leak into MQTT/InfluxDB.
        double scaled = BigDecimal.valueOf(decoded)
                .multiply(BigDecimal.valueOf(point.effectiveScale()))
                .add(BigDecimal.valueOf(point.effectiveOffset()))
                .doubleValue();
        if (!Double.isFinite(scaled)) {
            throw new IOException("non-finite Modbus value: " + point.name());
        }
        return scaled;
    }

    private static byte[] reorder(byte[] bytes, ModbusByteOrder order) throws IOException {
        int[] indices = switch (order) {
            case AB, ABCD -> bytes.length == 2 ? new int[]{0, 1} : new int[]{0, 1, 2, 3};
            case BA -> new int[]{1, 0};
            case CDAB -> new int[]{2, 3, 0, 1};
            case BADC -> new int[]{1, 0, 3, 2};
            case DCBA -> new int[]{3, 2, 1, 0};
        };
        if (indices.length != bytes.length) {
            throw new IOException("byte order does not match register width: " + order);
        }
        byte[] result = new byte[bytes.length];
        for (int i = 0; i < indices.length; i++) {
            result[i] = bytes[indices[i]];
        }
        return result;
    }
}
