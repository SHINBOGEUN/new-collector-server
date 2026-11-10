package net.vivans.dcim.module.ingest.lora.infrastructure.dto;

import java.time.Instant;

/** Sensor Data → Manager 연결/수신 상태 보고. */
public record LoraMqttSourceStatusReportRequest(
        String status,
        Instant lastConnectedAt,
        Instant lastMessageAt,
        long messageCount,
        long errorCount,
        long reconnectCount,
        String lastError,
        Instant reportedAt
) {
}
