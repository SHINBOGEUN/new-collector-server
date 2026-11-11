package net.vivans.dcim.module.ingest.mqtt.application;

import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerMqttClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerServiceProperties;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.*;
import net.vivans.dcim.module.ingest.mqtt.config.SensorMqttProperties;
import net.vivans.dcim.module.ingest.mqtt.domain.MqttExternalIdNormalizer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 전체 스냅샷을 교체하여 변경 전 MAC 매핑이 계속 남지 않게 한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class MqttConfigurationService {
    private final ManagerMqttClient client;
    private final ManagerServiceProperties managerProperties;
    private final SensorMqttProperties properties;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Instant.EPOCH);
    private final Map<Key, Instant> missingIdentities = new HashMap<>();

    public synchronized void refresh() {
        if (!managerProperties.isEnabled()) {
            snapshot = new Snapshot(Map.of(), Map.of(), Instant.EPOCH);
            missingIdentities.clear();
            return;
        }
        Map<Key, MqttIdentityResponse> identities = new HashMap<>();
        for (var identity : client.identities()) {
            if (identity.enabled() && identity.deviceId() != null && identity.deviceModelId() != null) {
                identities.put(new Key(identity.identityType(), normalize(identity.identityType(), identity.externalId())), identity);
            }
        }
        Map<Integer, List<MqttPointResponse>> points = new HashMap<>();
        for (var point : client.points()) {
            if (point.enabled()) points.computeIfAbsent(point.deviceModelId(), ignored -> new ArrayList<>()).add(point);
        }
        points.replaceAll((id, values) -> List.copyOf(values));
        snapshot = new Snapshot(Map.copyOf(identities), Map.copyOf(points),
                Instant.now().plusMillis(Math.max(1000, properties.getConfigTtlMs())));
        missingIdentities.clear();
    }

    public synchronized Optional<ResolvedConfiguration> resolve(String type, String externalId) {
        if (!managerProperties.isEnabled()) return Optional.empty();
        if (Instant.now().isAfter(snapshot.expiresAt())) refresh();
        var key = new Key(type, normalize(type, externalId));
        Instant missingUntil = missingIdentities.get(key);
        if (missingUntil != null && Instant.now().isBefore(missingUntil)) return Optional.empty();
        var identity = snapshot.identities().get(key);
        if (identity == null) identity = client.resolve(type, externalId).orElse(null);
        if (identity == null || !identity.enabled() || identity.deviceId() == null || identity.deviceModelId() == null) {
            if (missingIdentities.size() >= 1024) missingIdentities.clear();
            missingIdentities.put(key, snapshot.expiresAt());
            return Optional.empty();
        }
        // resolve로 방금 등록된 모델을 찾았다면 포인트도 최신 스냅샷으로 다시 받는다.
        if (!snapshot.points().containsKey(identity.deviceModelId())) refresh();
        Map<Key, MqttIdentityResponse> updated = new HashMap<>(snapshot.identities());
        updated.put(key, identity);
        snapshot = new Snapshot(Map.copyOf(updated), snapshot.points(), snapshot.expiresAt());
        return Optional.of(new ResolvedConfiguration(identity,
                snapshot.points().getOrDefault(identity.deviceModelId(), List.of())));
    }

    @Scheduled(fixedDelayString = "${ingest.mqtt.config-ttl-ms:60000}", initialDelayString = "${ingest.mqtt.config-ttl-ms:60000}")
    public void scheduledRefresh() {
        if (!managerProperties.isEnabled() || !properties.isEnabled()) return;
        try { refresh(); }
        catch (Exception exception) {
            log.warn("MQTT config refresh failed; expired configuration will not be used: {}", exception.getMessage());
        }
    }

    /** LoRa 경로와 같은 규칙을 쓰도록 공통 정규화에 위임한다. */
    public static String normalize(String type, String value) {
        return MqttExternalIdNormalizer.normalize(type, value);
    }

    private record Key(String type, String externalId) {}
    private record Snapshot(Map<Key, MqttIdentityResponse> identities,
                            Map<Integer, List<MqttPointResponse>> points, Instant expiresAt) {}
    public record ResolvedConfiguration(MqttIdentityResponse identity, List<MqttPointResponse> points) {}
}
