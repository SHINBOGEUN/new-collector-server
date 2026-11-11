package net.vivans.dcim.module.ingest.lora.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoraSentinelValuesTest {

    @Test
    void isNoData_recognizesTemperatureSentinel() {
        assertThat(LoraSentinelValues.isNoData(327.67)).isTrue();
    }

    @Test
    void isNoData_recognizesHumiditySentinel() {
        assertThat(LoraSentinelValues.isNoData(409.5)).isTrue();
    }

    @Test
    void isNoData_recognizesSentinelWithinFloatingPointTolerance() {
        assertThat(LoraSentinelValues.isNoData(327.6700001)).isTrue();
    }

    @Test
    void isNoData_returnsFalseForRealMeasurements() {
        assertThat(LoraSentinelValues.isNoData(23.5)).isFalse();
        assertThat(LoraSentinelValues.isNoData(0.0)).isFalse();
        assertThat(LoraSentinelValues.isNoData(-10.0)).isFalse();
    }
}
