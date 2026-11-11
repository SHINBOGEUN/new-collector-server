package net.vivans.dcim.module.ingest.lora.domain;

/**
 * Dragino 센서(LHT65N 계열)가 "값 없음"을 나타낼 때 쓰는 고정 sentinel 값.
 * 레거시 sensor-data-service의 isNoData()와 동일 (327.67=온도 없음, 409.5=습도 없음).
 * 장비마다 달라지는 값이 아니라 Dragino 펌웨어 차원의 고정 규약이라 설정으로 빼지 않고 상수로 유지한다.
 */
public final class LoraSentinelValues {

    private static final double NO_TEMP = 327.67;
    private static final double NO_HUMIDITY = 409.5;
    private static final double EPSILON = 1e-6;

    private LoraSentinelValues() {
    }

    public static boolean isNoData(double value) {
        return isEqual(value, NO_TEMP) || isEqual(value, NO_HUMIDITY);
    }

    private static boolean isEqual(double a, double b) {
        return Math.abs(a - b) < EPSILON;
    }
}
