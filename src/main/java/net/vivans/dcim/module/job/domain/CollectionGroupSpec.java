package net.vivans.dcim.module.job.domain;

import java.util.List;

public record CollectionGroupSpec(
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
        List<CollectionGroupTargetSpec> targets,
        List<String> skipped
) {
}
