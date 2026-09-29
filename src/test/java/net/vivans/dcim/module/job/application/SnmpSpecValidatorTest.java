package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.snmp.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionTargetSpec;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnmpSpecValidatorTest {
    private final SnmpSpecValidator validator = new SnmpSpecValidator(new OidTemplateResolver());
    private final CollectionGroupOidSpec fixed = new CollectionGroupOidSpec("TEMP", "1.3.6.1.0", false, null);
    private final CollectionGroupOidSpec indexed = new CollectionGroupOidSpec("POWER", "1.3.6.1.{instanceId}.0", true, 0.1);

    @Test
    void acceptsFixedAndInstanceOidsWithDefaultCommunity() {
        assertThatCode(() -> validator.validate(spec(List.of(fixed, indexed),
                List.of(target(0), target(7))))).doesNotThrowAnyException();
    }

    @Test
    void acceptsNoTargets() {
        assertThatCode(() -> validator.validate(spec(null, null))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(spec(List.of(), List.of()))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingInstanceOnlyWhenRequired() {
        assertThatCode(() -> validator.validate(spec(List.of(fixed), List.of(target(null)))))
                .doesNotThrowAnyException();
        rejects(spec(List.of(indexed), List.of(target(null))), "instanceId");
    }

    @Test
    void rejectsMissingOrMalformedOids() {
        rejects(spec(null, List.of(target(1))), "oids");
        rejects(spec(Collections.singletonList(null), List.of(target(1))), "OID");
        for (String template : new String[] {null, "", "1.3.bad.0", "1..3", "1.3.{other}.0"}) {
            rejects(spec(List.of(new CollectionGroupOidSpec("TEMP", template, false, 1.0)),
                    List.of(target(1))), "OID");
        }
    }

    @Test
    void rejectsTemplateFlagMismatch() {
        for (var oid : List.of(
                new CollectionGroupOidSpec("T", "1.3.6.0", true, null),
                new CollectionGroupOidSpec("T", "1.3.6.{instanceId}", false, null))) {
            rejects(spec(List.of(oid), List.of(target(1))), "requiresInstance");
        }
    }

    @Test
    void rejectsDuplicateNamesAndInvalidScales() {
        rejects(spec(List.of(fixed, fixed), List.of(target(1))), "duplicate");
        for (double scale : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            rejects(spec(List.of(new CollectionGroupOidSpec("T", "1.3.6.0", false, scale)),
                    List.of(target(1))), "scale");
        }
    }

    @Test
    void rejectsInvalidTargets() {
        for (var target : new SnmpCollectionTargetSpec[] {null,
                new SnmpCollectionTargetSpec(null, "host", 161, 1),
                new SnmpCollectionTargetSpec(0, "host", 161, 1),
                new SnmpCollectionTargetSpec(1, " ", 161, 1),
                new SnmpCollectionTargetSpec(1, "host", 0, 1),
                new SnmpCollectionTargetSpec(1, "host", 65536, 1),
                target(-1)}) {
            rejects(spec(List.of(fixed), Collections.singletonList(target)), "targets[0]");
        }
    }

    @Test
    void rejectsInvalidPointNames() {
        for (String name : new String[] {null, "", "A B", "A,B", "A=B", "A\"B", "A\tB"}) {
            rejects(spec(List.of(new CollectionGroupOidSpec(name, "1.3.6.0", false, null)),
                    List.of(target(1))), "name");
        }
    }

    private SnmpCollectionTargetSpec target(Integer instance) {
        return new SnmpCollectionTargetSpec(1, "localhost", 161, instance);
    }

    private SnmpCollectionGroupSpec spec(List<CollectionGroupOidSpec> oids,
                                         List<SnmpCollectionTargetSpec> targets) {
        return new SnmpCollectionGroupSpec(1, 2, 3, "snmp", "0 * * * * *",
                null, 2000, 1, 10, oids, targets, List.of());
    }

    private void rejects(SnmpCollectionGroupSpec spec, String message) {
        assertThatThrownBy(() -> validator.validate(spec))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
}
