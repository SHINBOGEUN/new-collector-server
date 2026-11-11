package net.vivans.dcim.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredSettingsValidatorTest {

    @Test
    void reportsCollectorAndIngestKeysTogether() {
        var environment = new MockEnvironment().withProperty("influx.url", "http://influx:8086");
        assertThatThrownBy(() -> new RequiredSettingsValidator().postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COLLECTOR_API_KEY")
                .hasMessageContaining("MANAGER_API_KEY")
                .hasMessageContaining("INFLUX_TOKEN");
    }

    @Test
    void ingestSettingsAreSkippedWhenTheirFeatureIsOff() {
        var environment = new MockEnvironment()
                .withProperty("security.api-key", "collector-key")
                .withProperty("manager.service.enabled", "false")
                .withProperty("influx.enabled", "false");
        assertThat(RequiredSettingsValidator.missing(environment)).isEmpty();
    }
}
