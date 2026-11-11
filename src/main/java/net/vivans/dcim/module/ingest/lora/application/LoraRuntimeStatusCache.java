package net.vivans.dcim.module.ingest.lora.application;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 등록된 LoRa 장비별 최근 수집 상태를 메모리에만 보관하는 런타임 상태 캐시.
 * DB에 저장하지 않으며 raw payload도 보관하지 않는다. Sensor Data가 재시작되면 초기화되고,
 * 이후 상태는 InfluxDB 마지막 저장 이력과 MQTT 소스 상태를 기준으로 Manager 쪽에서 다시 계산한다.
 * 등록된(config cache에 매칭된) device만 key로 사용해 캐시 크기를 제한한다 — 미등록 devEUI/deviceName은
 * 절대 이 캐시에 추가하지 않는다.
 */
@Component
public class LoraRuntimeStatusCache {

    public record Snapshot(
            Integer deviceId,
            Instant lastReceivedAt,
            Instant lastSavedAt,
            Integer lastPointCount,
            String lastErrorCode,
            String lastErrorMessage,
            Instant lastErrorAt
    ) {
    }

    private static final class Entry {
        volatile Instant lastReceivedAt;
        volatile Instant lastSavedAt;
        volatile Integer lastPointCount;
        volatile String lastErrorCode;
        volatile String lastErrorMessage;
        volatile Instant lastErrorAt;
    }

    private final Map<Integer, Entry> entriesByDeviceId = new ConcurrentHashMap<>();

    /** 등록된 장비로부터 메시지를 수신했을 때 호출한다(미등록 장비는 호출하지 않는다). */
    public void recordReceived(Integer deviceId, Instant receivedAt) {
        entry(deviceId).lastReceivedAt = receivedAt;
    }

    /** NO_MAPPING / FIELD_CONVERSION_FAILED / INFLUX_WRITE_FAILED 발생 시 호출한다. */
    public void recordError(Integer deviceId, String errorCode, String errorMessage, Instant occurredAt) {
        Entry entry = entry(deviceId);
        entry.lastErrorCode = errorCode;
        entry.lastErrorMessage = errorMessage;
        entry.lastErrorAt = occurredAt;
    }

    /**
     * InfluxDB 저장 성공 시 호출한다. clearError=true면 이전 오류 상태를 정상으로 해제한다.
     * 같은 메시지 처리 중 방금 새로 발생한 오류(FIELD_CONVERSION_FAILED 등)가 있으면 호출자가
     * clearError=false로 넘겨, 이번에 막 기록한 오류를 저장 성공 처리로 덮어쓰지 않도록 한다.
     */
    public void recordSaved(Integer deviceId, Instant savedAt, int pointCount, boolean clearError) {
        Entry entry = entry(deviceId);
        entry.lastSavedAt = savedAt;
        entry.lastPointCount = pointCount;
        if (clearError) {
            entry.lastErrorCode = null;
            entry.lastErrorMessage = null;
            entry.lastErrorAt = null;
        }
    }

    public List<Snapshot> snapshotAll() {
        return entriesByDeviceId.entrySet().stream()
                .map(e -> toSnapshot(e.getKey(), e.getValue()))
                .toList();
    }

    /** 테스트 및 단건 조회용. */
    public Snapshot snapshotOf(Integer deviceId) {
        return toSnapshot(deviceId, entry(deviceId));
    }

    private Entry entry(Integer deviceId) {
        return entriesByDeviceId.computeIfAbsent(deviceId, id -> new Entry());
    }

    private static Snapshot toSnapshot(Integer deviceId, Entry entry) {
        return new Snapshot(deviceId, entry.lastReceivedAt, entry.lastSavedAt, entry.lastPointCount,
                entry.lastErrorCode, entry.lastErrorMessage, entry.lastErrorAt);
    }
}
