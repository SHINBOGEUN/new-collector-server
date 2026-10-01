package net.vivans.dcim.module.job.domain.modbus;

import java.util.List;

/**
 * Modbus 수집 항목 하나.
 *
 * 그룹 공통 points와 target별 points에서 동일하게 사용한다.
 * address는 Manager가 결정한 최종 요청 주소다.
 */
public record CollectionGroupModbusPointSpec(
        String name,
        ModbusRegisterType registerType,
        Integer address,
        ModbusDataType dataType,
        ModbusByteOrder byteOrder,
        Double scale,
        Double offset,
        List<ModbusBitFieldSpec> bitFields
) {
    public CollectionGroupModbusPointSpec(String name, ModbusRegisterType registerType, Integer address,
                                          ModbusDataType dataType, ModbusByteOrder byteOrder, Double scale,
                                          Double offset) {
        this(name, registerType, address, dataType, byteOrder, scale, offset, List.of());
    }

    public CollectionGroupModbusPointSpec(String name, ModbusRegisterType registerType, Integer address,
                                          ModbusDataType dataType, ModbusByteOrder byteOrder, Double scale) {
        this(name, registerType, address, dataType, byteOrder, scale, null, List.of());
    }

    /**
     * 배율 생략 또는 null은 1로 해석한다.
     */
    public double effectiveScale() {
        return scale == null ? 1.0 : scale;
    }

    /** 최종값 = 원시값 × scale + offset. */
    public double effectiveOffset() {
        return offset == null ? 0.0 : offset;
    }

    /**
     * 기존 16비트 설정의 null은 AB(교환 없음)로 해석한다.
     * 32비트의 null은 기본값으로 보정하지 않고 검증 단계에서 거부한다.
     */
    public ModbusByteOrder effectiveByteOrder() {
        if (byteOrder == null && dataType != null && !dataType.isMultiRegister()) {
            return ModbusByteOrder.AB;
        }
        return byteOrder;
    }
}
