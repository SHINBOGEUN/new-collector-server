package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScaledValuesTest {

    @Test
    void apply_multipliesNumericValues() {
        Map<String, Object> scaled = ScaledValues.apply(
                Map.of("W", 5195, "V", 220),
                List.of(
                        new CollectionGroupOidSpec("W", "1.2.3", false, 0.1),
                        new CollectionGroupOidSpec("V", "1.2.4", false, null)
                )
        );

        assertThat(scaled.get("W")).isEqualTo(519.5);
        assertThat(scaled.get("V")).isEqualTo(220);
    }

    @Test
    void apply_skipsWhenScaleIsOne() {
        Map<String, Object> scaled = ScaledValues.apply(
                Map.of("W", 100),
                List.of(new CollectionGroupOidSpec("W", "1.2.3", false, 1.0))
        );

        assertThat(scaled.get("W")).isEqualTo(100);
    }
}
