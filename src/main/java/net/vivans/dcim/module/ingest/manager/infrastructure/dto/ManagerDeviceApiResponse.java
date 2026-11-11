package net.vivans.dcim.module.ingest.manager.infrastructure.dto;

import net.vivans.dcim.shared.api.ApiResponse;

public record ManagerDeviceApiResponse(
        int status,
        ManagerDeviceResponse data
) {
}
