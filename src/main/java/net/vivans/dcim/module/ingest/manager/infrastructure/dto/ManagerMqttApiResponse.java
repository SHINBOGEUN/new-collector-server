package net.vivans.dcim.module.ingest.manager.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ManagerMqttApiResponse<T>(Integer status, T data) {
}
