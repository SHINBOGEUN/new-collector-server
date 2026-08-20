package net.vivans.dcim.module.job.domain;

public record CollectionGroupTargetSpec(
        Integer deviceId,
        String host,
        int port,
        Integer instanceId
) {
}
