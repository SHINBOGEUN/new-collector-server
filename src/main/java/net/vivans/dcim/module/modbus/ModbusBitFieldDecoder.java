package net.vivans.dcim.module.modbus;

import net.vivans.dcim.module.job.domain.modbus.ModbusBitFieldSpec;

/** 정수 레지스터의 LSB 기준 비트 구간을 숫자 point로 변환한다. */
public final class ModbusBitFieldDecoder {
    private ModbusBitFieldDecoder() {
    }

    public static long decode(Number source, int sourceWidth, ModbusBitFieldSpec field) {
        long raw = source.longValue();
        long unsigned = sourceWidth == 16 ? raw & 0xffffL : raw & 0xffffffffL;
        long extracted = (unsigned >>> field.bitOffset()) & ((1L << field.bitWidth()) - 1);
        Long mapped = field.valueMap() == null ? null : field.valueMap().get(Long.toString(extracted));
        return mapped != null ? mapped : field.unmappedValue() != null ? field.unmappedValue() : extracted;
    }
}
