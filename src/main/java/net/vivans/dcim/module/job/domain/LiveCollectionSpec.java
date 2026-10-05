package net.vivans.dcim.module.job.domain;

import java.util.List;

public record LiveCollectionSpec(
        int intervalMs,
        String protocol,
        String community,
        int timeoutMs,
        int retries,
        int maxConcurrency,
        List<LiveCollectionTargetSpec> targets,
        List<LiveModbusTargetSpec> modbusTargets
) {
    public LiveCollectionSpec(int intervalMs, String protocol, String community, int timeoutMs,
                              int retries, int maxConcurrency, List<LiveCollectionTargetSpec> targets) {
        this(intervalMs, protocol, community, timeoutMs, retries, maxConcurrency, targets, List.of());
    }
}
