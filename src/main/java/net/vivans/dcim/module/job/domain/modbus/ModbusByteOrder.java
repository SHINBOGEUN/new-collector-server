package net.vivans.dcim.module.job.domain.modbus;

/**
 * 장비에서 읽은 데이터의 바이트 배치.
 *
 * 16비트: AB, BA
 * 32비트: ABCD, CDAB, BADC, DCBA
 */
public enum ModbusByteOrder {

    AB,
    BA,
    ABCD,
    CDAB,
    BADC,
    DCBA;

    public boolean isSingleRegisterOrder() {
        return this == AB || this == BA;
    }
}
