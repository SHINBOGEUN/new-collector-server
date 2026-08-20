package net.vivans.dcim.module.job.api.dto;

import java.util.Map;

public record HealthResponse(String status, int jobs, Map<String, Object> details) {
}
