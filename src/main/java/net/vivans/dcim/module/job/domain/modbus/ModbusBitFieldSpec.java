package net.vivans.dcim.module.job.domain.modbus;

import java.util.Map;

/** 원본 레지스터에서 추출해 별도 point로 저장할 비트 구간. LSB가 0번 비트다. */
public record ModbusBitFieldSpec(
        String name,
        int bitOffset,
        int bitWidth,
        Map<String, Long> valueMap,
        Long unmappedValue
) {
}
