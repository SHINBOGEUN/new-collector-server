package net.vivans.dcim.module.job.api.dto;
import java.time.Instant;
public record DeviceResultResponse(String collectorJobId,Integer taskId,Integer groupId,String protocol,Integer deviceId,
        int targetCount,int failedTargetCount,Instant lastAttemptAt,String lastOutcome,Instant lastSuccessAt,
        Instant lastFailureAt,String lastFailureCode,String lastFailureReason,int consecutiveFailures,
        boolean inFlight,Instant inFlightSince,Instant backoffUntil,int skippedBackoff,int skippedOverrun,
        int failureThreshold,long intervalSeconds,boolean jobEnabled) {}
