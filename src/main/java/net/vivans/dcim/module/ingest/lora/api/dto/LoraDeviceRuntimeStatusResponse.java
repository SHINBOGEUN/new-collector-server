package net.vivans.dcim.module.ingest.lora.api.dto;

import java.time.Instant;

/**
 * Manager가 "LoRa 수집 상태" 화면을 조합할 때 사용하는 장비별 런타임 상태 한 건.
 * Sensor Data 메모리 상태이며 재시작 시 초기화된다. raw payload, MQTT 비밀번호 등은 포함하지 않는다.
 */
public record LoraDeviceRuntimeStatusResponse(
        Integer deviceId,
        Instant lastReceivedAt,
        Instant lastSavedAt,
        Integer lastPointCount,
        String lastErrorCode,
        String lastErrorMessage,
        Instant lastErrorAt
) {
}
