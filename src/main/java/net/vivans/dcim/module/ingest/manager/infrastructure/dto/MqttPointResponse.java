package net.vivans.dcim.module.ingest.manager.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MqttPointResponse(Integer id, Integer deviceModelId, String pointName, String payloadField,
        String componentScope, String componentType, String componentPath,
        String componentIndexField, String componentUuidField,
        Double scale, String valueMap, boolean enabled) {
}
