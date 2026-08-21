package net.vivans.dcim.module.job.domain;

import java.util.List;

public record LiveCollectionSpec(
        int intervalMs,
        String protocol,
        String community,
        int timeoutMs,
        int retries,
        int maxConcurrency,
        List<LiveCollectionTargetSpec> targets
) {
}
