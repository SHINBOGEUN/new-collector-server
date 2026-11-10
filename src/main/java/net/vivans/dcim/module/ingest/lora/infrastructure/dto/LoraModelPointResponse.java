package net.vivans.dcim.module.ingest.lora.infrastructure.dto;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record LoraModelPointResponse(
        Integer id,
        Integer deviceModelId,
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
