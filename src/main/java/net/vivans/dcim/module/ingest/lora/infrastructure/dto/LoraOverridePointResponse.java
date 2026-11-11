package net.vivans.dcim.module.ingest.lora.infrastructure.dto;

public record LoraOverridePointResponse(
        Integer id,
        Integer deviceId,
        String payloadField,
        String pointName,
        Integer dataPointTypeId,
        String dataPointTypeCode,
        String unit,
        Double scale,
        String valueMap,
        boolean enabled
) {
}
