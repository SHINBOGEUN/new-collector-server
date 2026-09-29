package net.vivans.dcim.module.job.domain.snmp;

import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;

public record SnmpCollectionTargetSpec(
        Integer deviceId,
        String host,
        int port,
        Integer instanceId
) implements CollectionGroupTargetSpec {
}
