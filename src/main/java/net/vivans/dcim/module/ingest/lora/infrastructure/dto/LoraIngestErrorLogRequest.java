package net.vivans.dcim.module.ingest.lora.infrastructure.dto;

import net.vivans.dcim.module.ingest.lora.domain.LoraIdType;

import java.time.Instant;

public record LoraIngestErrorLogRequest(
        Instant receivedAt,
        Integer deviceId,
        String externalId,
        LoraIdType idType,
        String reason,
        String rawPayload
) {
}
