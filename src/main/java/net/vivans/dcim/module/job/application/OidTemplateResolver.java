package net.vivans.dcim.module.job.application;

import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import org.springframework.stereotype.Component;

@Component
public class OidTemplateResolver {

    public String resolve(CollectionGroupOidSpec oid, CollectionGroupTargetSpec target) {
        String template = oid.template();
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("OID template이 비어 있습니다: " + oid.name());
        }
        if (!oid.requiresInstance()) {
            return template;
        }
        if (target.instanceId() == null) {
            throw new IllegalArgumentException("instanceId가 필요한 OID입니다: " + oid.name());
        }
        return template.replace("{instanceId}", String.valueOf(target.instanceId()));
    }
}
