package net.vivans.dcim.module.job.api.dto;

public record JobResponse(
        String collectorJobId,
        Integer taskId,
        Integer groupId,
        Integer modelId,
        String protocol,
        String cronExpression,
        boolean enabled,
        int targetCount
) {
}
