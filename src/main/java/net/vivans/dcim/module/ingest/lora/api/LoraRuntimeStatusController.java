package net.vivans.dcim.module.ingest.lora.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.ingest.lora.api.dto.LoraDeviceRuntimeStatusResponse;
import net.vivans.dcim.module.ingest.lora.application.LoraRuntimeStatusCache;
import net.vivans.dcim.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manager 내부 호출 전용 LoRa 런타임 상태 조회 API — 외부 공개용이 아니다.
 * API Key 인증은 다른 /api/internal/lora/** 엔드포인트(LoraMqttSourceSyncController 등)와 동일하게
 * 공통 SecurityConfig/ApiKeyAuthFilter가 적용한다(이 컨트롤러에 별도 인증 코드를 두지 않는다).
 * raw payload, MQTT 비밀번호, broker URL 등 민감 정보는 응답에 포함하지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/lora/runtime-status")
@Tag(name = "lora-runtime-status", description = "Manager 전용 LoRa 장비별 런타임 수집 상태 조회 API")
public class LoraRuntimeStatusController {

    private final LoraRuntimeStatusCache runtimeStatusCache;

    @GetMapping
    @Operation(summary = "등록된 LoRa 장비별 런타임 수집 상태 목록 조회(메모리 상태, Manager 내부 호출용)")
    public ApiResponse<List<LoraDeviceRuntimeStatusResponse>> getAll() {
        List<LoraDeviceRuntimeStatusResponse> items = runtimeStatusCache.snapshotAll().stream()
                .map(snapshot -> new LoraDeviceRuntimeStatusResponse(
                        snapshot.deviceId(),
                        snapshot.lastReceivedAt(),
                        snapshot.lastSavedAt(),
                        snapshot.lastPointCount(),
                        snapshot.lastErrorCode(),
                        snapshot.lastErrorMessage(),
                        snapshot.lastErrorAt()
                ))
                .toList();
        return ApiResponse.ok(items);
    }
}
