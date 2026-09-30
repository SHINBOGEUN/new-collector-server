package net.vivans.dcim.module.job.api;

import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.job.api.dto.ModbusReadPreviewRequest;
import net.vivans.dcim.module.job.api.dto.ModbusReadPreviewResponse;
import net.vivans.dcim.module.job.application.ModbusReadPreviewService;
import net.vivans.dcim.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/modbus")
public class ModbusReadPreviewController {
    private final ModbusReadPreviewService service;

    @PostMapping("/read-preview")
    public ApiResponse<ModbusReadPreviewResponse> read(@RequestBody ModbusReadPreviewRequest request) {
        return ApiResponse.ok(service.read(request));
    }
}
