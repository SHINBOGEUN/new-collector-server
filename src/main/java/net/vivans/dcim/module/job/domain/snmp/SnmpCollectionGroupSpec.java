package net.vivans.dcim.module.job.domain.snmp;

import net.vivans.dcim.module.job.domain.CollectionGroupSpec;

import java.util.List;

/** 기존 SNMP 그룹 JSON과 동일한 필드를 사용하는 spec. */
public record SnmpCollectionGroupSpec(
        Integer taskId,
        Integer groupId,
        Integer modelId,
        String protocol,
        String cronExpression,
        String community,
        int timeoutMs,
        int retries,
        int maxConcurrency,
        List<CollectionGroupOidSpec> oids,
        List<SnmpCollectionTargetSpec> targets,
        List<String> skipped
) implements CollectionGroupSpec {
    public SnmpCollectionGroupSpec {
        if (!"snmp".equals(protocol)) {
            throw new IllegalArgumentException("protocol must be snmp");
        }
    }
}
