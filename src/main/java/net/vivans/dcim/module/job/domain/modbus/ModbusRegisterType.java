package net.vivans.dcim.module.job.domain.modbus;

/**
 * Modbus 읽기 대상 종류.
 */
public enum ModbusRegisterType {

    COIL,
    DISCRETE,
    HOLDING,
    INPUT;

    /**
     * COIL과 DISCRETE는 레지스터 숫자가 아닌 비트 값을 읽는다.
     */
    public boolean isBitType() {
        return this == COIL || this == DISCRETE;
    }
}
