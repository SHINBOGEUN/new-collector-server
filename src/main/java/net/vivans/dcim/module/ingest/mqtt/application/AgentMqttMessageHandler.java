package net.vivans.dcim.module.ingest.mqtt.application;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.influx.application.InfluxWriteService;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerDeviceClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.GpuAgentDiscoveryClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AgentMqttMessageHandler {
    private final MqttConfigurationService configurationService;
    private final MqttPointExtractor extractor;
    private final ManagerDeviceClient devices;
    private final InfluxWriteService writer;
    private final GpuAgentDiscoveryClient discovery;

    public void handle(JsonNode root) {
        // 잘못된 시간을 현재 시각으로 대체하지 않는다.
        var time = OffsetDateTime.parse(root.path("time").asText()).toInstant();
        JsonNode info = root.path("deviceInfo");
        String type;
        String externalId;
        if (info.hasNonNull("identityType") && info.hasNonNull("externalId")) {
            type = info.path("identityType").asText();
            externalId = info.path("externalId").asText();
        } else {
            throw new IllegalArgumentException("Agent deviceInfo.identityType/externalId are required; LoRa uses its dedicated handler");
        }
        var resolved = configurationService.resolve(type, externalId).orElse(null);
        if ("UUID".equals(type) && root.path("components").isArray()) {
            try {
                String uuid = java.util.UUID.fromString(externalId).toString();
                var inventory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                inventory.set("system", root.path("metadata"));
                var gpus = inventory.putArray("gpus");
                root.path("components").forEach(component -> {
                    if (!"GPU".equals(component.path("type").asText())) return;
                    var gpu = gpus.addObject();
                    gpu.set("index", component.path("index"));
                    gpu.set("uuid", component.path("uuid"));
                    gpu.set("name", component.path("metadata").path("name"));
                });
                discovery.discover(uuid, info.path("deviceName").asText("GPU server"), inventory);
            } catch (IllegalArgumentException e) {
                log.debug("Invalid GPU agent UUID");
            }
        }
        if (resolved == null) {
            log.debug("Unregistered or disabled MQTT device skipped identityType={}", type);
            return;
        }
        var identity = resolved.identity();
        var measurements = extractor.extract(root, resolved.points());
        if (measurements.isEmpty()) {
            log.debug("MQTT device has no configured valid values deviceId={}", identity.deviceId());
            return;
        }
        var device = devices.findDevice(identity.deviceId()).orElse(null);
        if (device != null && !device.enabled()) return;
        writer.writeMqttPoints(identity.deviceId(), identity.deviceModelId(), device, measurements, time);
    }
}

