package net.vivans.dcim.module.job.api.dto;

import java.time.Instant;

public record JobResponse(
        String collectorJobId,
        Integer taskId,
        Integer groupId,
        Integer modelId,
        String protocol,
        String cronExpression,
        boolean enabled,
        int targetCount,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        int consecutiveFailureCount,
        String lastFailureReason
) {
}
