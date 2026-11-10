package net.vivans.dcim.module.ingest.mqtt.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.MqttPointResponse;
import net.vivans.dcim.module.ingest.mqtt.domain.MqttMeasurement;
import org.springframework.stereotype.Component;

import java.util.*;

/** 정의된 경로만 읽는다. metadata나 미정의 object 항목을 임의로 저장하지 않는다. */
@Component
@Slf4j
@RequiredArgsConstructor
public class MqttPointExtractor {
    private final ObjectMapper mapper;

    public List<MqttMeasurement> extract(JsonNode root, List<MqttPointResponse> definitions) {
        List<MqttMeasurement> measurements = new ArrayList<>();
        for (var definition : definitions) {
            if (!definition.enabled() || definition.pointName() == null || definition.pointName().isBlank()) continue;
            try {
                if ("DEVICE".equals(definition.componentScope())) {
                    add(measurements, root, definition, null, null, null);
                } else if ("COMPONENT".equals(definition.componentScope())) {
                    JsonNode components = readPath(root, definition.componentPath());
                    if (!components.isArray() || definition.componentType() == null) continue;
                    Set<String> seen = new HashSet<>();
                    Set<String> duplicates = new HashSet<>();
                    for (var component : components) {
                        if (!matchesType(component, definition.componentType())) continue;
                        String index = scalar(readPath(component, definition.componentIndexField()));
                        if (index != null && !seen.add(index)) duplicates.add(index);
                    }
                    for (var component : components) {
                        if (!matchesType(component, definition.componentType())) continue;
                        String index = scalar(readPath(component, definition.componentIndexField()));
                        if (index == null || duplicates.contains(index)) continue;
                        if ("GPU".equals(definition.componentType()) && !index.matches("0|[1-9][0-9]*")) continue;
                        String uuid = scalar(readPath(component, definition.componentUuidField()));
                        if (definition.componentUuidField() != null && !definition.componentUuidField().isBlank() && uuid == null) continue;
                        add(measurements, component, definition, definition.componentType(), index, uuid);
                    }
                }
            } catch (Exception exception) {
                log.warn("MQTT point skipped pointId={} pointName={} reason={}",
                        definition.id(), definition.pointName(), exception.getMessage());
            }
        }
        return List.copyOf(measurements);
    }

    private static boolean matchesType(JsonNode item, String type) {
        return item.isObject() && (!item.has("type") || type.equals(item.path("type").asText()));
    }

    private void add(List<MqttMeasurement> output, JsonNode source, MqttPointResponse definition,
                     String type, String index, String uuid) throws Exception {
        JsonNode raw = readPath(source, definition.payloadField());
        if (raw.isNull() || raw.isMissingNode() || raw.isContainerNode() || raw.isBoolean()) return;
        JsonNode value = raw;
        if (definition.valueMap() != null && !definition.valueMap().isBlank()) {
            value = mapper.readTree(definition.valueMap()).path(raw.asText());
            if (value.isMissingNode() || value.isNull()) return;
        }
        double number;
        try { number = Double.parseDouble(value.asText()); }
        catch (NumberFormatException exception) { return; }
        number *= definition.scale() == null ? 1D : definition.scale();
        if (!Double.isFinite(number)) return;
        output.add(new MqttMeasurement(definition.pointName(), number, type, index, uuid));
    }

    /** 단순 dot 경로, $. 접두사, 숫자 배열 인덱스와 JSON Pointer 지원. */
    public static JsonNode readPath(JsonNode root, String path) {
        if (path == null || path.isBlank()) return MissingNode.getInstance();
        if (path.startsWith("/")) return root.at(path);
        String normalized = path.startsWith("$.") ? path.substring(2) : path;
        normalized = normalized.replaceAll("\\[(\\d+)\\]", ".$1");
        JsonNode current = root;
        for (String part : normalized.split("\\.")) {
            if (current.isArray() && part.matches("\\d+")) current = current.path(Integer.parseInt(part));
            else current = current.path(part);
        }
        return current;
    }

    private static String scalar(JsonNode node) {
        if (!node.isTextual() && !node.isIntegralNumber()) return null;
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }
}
