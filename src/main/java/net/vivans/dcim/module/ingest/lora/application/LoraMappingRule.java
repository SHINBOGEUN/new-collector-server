package net.vivans.dcim.module.ingest.lora.application;

/** override/model 매핑을 병합해 하나의 형태로 다루기 위한 내부 표현. */
public record LoraMappingRule(
        String payloadField,
        String pointName,
        String dataPointTypeCode,
        String unit,
        Double scale,
        String valueMap
) {
}
