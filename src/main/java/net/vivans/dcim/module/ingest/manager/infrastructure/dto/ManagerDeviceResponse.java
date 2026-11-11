package net.vivans.dcim.module.ingest.manager.infrastructure.dto;

public record ManagerDeviceResponse(
        Integer id,
        Integer modelId,
        String modelName,
        String manufacturer,
        String deviceTypeCode,
        String locationNodeCode,
        String locationNodeName,
        String name,
        String description,
        boolean enabled
) {
}
