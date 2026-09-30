package net.vivans.dcim.module.job.api.dto;

import java.util.List;
import java.util.Map;

public record ModbusReadPreviewResponse(List<TargetResult> targets) {
    public record TargetResult(Integer deviceId, String host, int port, int unitId,
                               Map<String, Object> values, String error, long elapsedMs) {
    }
}
