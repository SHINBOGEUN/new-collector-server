package net.vivans.dcim.module.ingest.manager.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MqttIdentityResponse(Integer identityId, String identityType, String externalId,
        String normalizedExternalId, Integer deviceId, String deviceName,
        Integer deviceModelId, String deviceModelName, boolean enabled) {
}
