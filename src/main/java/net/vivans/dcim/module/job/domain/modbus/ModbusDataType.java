package net.vivans.dcim.module.job.domain.modbus;

/**
 * Modbus 레지스터 값의 해석 자료형.
 */
public enum ModbusDataType {

    INT16(1),
    UINT16(1),
    INT32(2),
    UINT32(2),
    FLOAT32(2);

    private final int registerCount;

    ModbusDataType(int registerCount) {
        this.registerCount = registerCount;
    }

    public int getRegisterCount() {
        return registerCount;
    }

    public boolean isMultiRegister() {
        return registerCount > 1;
    }
}
