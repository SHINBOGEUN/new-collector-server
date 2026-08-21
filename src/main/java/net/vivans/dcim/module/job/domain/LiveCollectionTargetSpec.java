package net.vivans.dcim.module.job.domain;

import java.util.List;

public record LiveCollectionTargetSpec(
        Integer deviceId,
        String deviceName,
        String host,
        int port,
        Integer instanceId,
        List<LiveCollectionPointSpec> points
) {
}
