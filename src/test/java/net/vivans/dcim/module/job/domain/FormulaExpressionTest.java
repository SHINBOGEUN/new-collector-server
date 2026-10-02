package net.vivans.dcim.module.job.domain;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormulaExpressionTest {
    @Test
    void calculatesArithmeticAndReferences() {
        assertThat(FormulaExpression.references("(FACILITY - IT) / IT"))
                .isEqualTo(Set.of("FACILITY", "IT"));
        assertThat(FormulaExpression.evaluate("(FACILITY - IT) / IT",
                Map.of("FACILITY", 120D, "IT", 100D))).isEqualTo(0.2D);
    }

    @Test
    void rejectsMissingInputsAndZeroDenominator() {
        assertThatThrownBy(() -> FormulaExpression.evaluate("A + B", Map.of("A", 1D)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaExpression.evaluate("A / B", Map.of("A", 1D, "B", 0D)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero");
    }

    @Test
    void rejectsCodeAndInvalidSyntax() {
        assertThatThrownBy(() -> FormulaExpression.references("java.lang.Runtime.exec()"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormulaExpression.references("A + (B"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
