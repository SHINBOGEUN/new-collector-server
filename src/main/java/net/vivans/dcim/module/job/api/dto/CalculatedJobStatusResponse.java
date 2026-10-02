package net.vivans.dcim.module.job.api.dto;

import java.time.Instant;

public record CalculatedJobStatusResponse(
        Integer definitionId,
        Integer configVersion,
        boolean running,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        int consecutiveFailureCount,
        String lastFailureReason
) {
}
