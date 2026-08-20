package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidTemplateResolverTest {

    private final OidTemplateResolver resolver = new OidTemplateResolver();

    @Test
    void scalarOidKeepsTemplate() {
        CollectionGroupOidSpec oid = new CollectionGroupOidSpec("V", "1.3.6.1.4.1.6375.1.1.0", false);
        CollectionGroupTargetSpec target = new CollectionGroupTargetSpec(3, "192.168.14.114", 161, null);

        assertThat(resolver.resolve(oid, target)).isEqualTo("1.3.6.1.4.1.6375.1.1.0");
    }

    @Test
    void instanceOidSubstitutesPlaceholder() {
        CollectionGroupOidSpec oid = new CollectionGroupOidSpec("V", "1.3.6.1.4.1.6375.1.1.{instanceId}", true);
        CollectionGroupTargetSpec target = new CollectionGroupTargetSpec(3, "192.168.14.114", 161, 7);

        assertThat(resolver.resolve(oid, target)).isEqualTo("1.3.6.1.4.1.6375.1.1.7");
    }

    @Test
    void instanceOidWithoutIdFails() {
        CollectionGroupOidSpec oid = new CollectionGroupOidSpec("V", "1.3.6.1.4.1.6375.1.1.{instanceId}", true);
        CollectionGroupTargetSpec target = new CollectionGroupTargetSpec(3, "192.168.14.114", 161, null);

        assertThatThrownBy(() -> resolver.resolve(oid, target))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
