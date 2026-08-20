package net.vivans.dcim.module.job.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.job.api.dto.HealthResponse;
import net.vivans.dcim.module.job.api.dto.JobResponse;
import net.vivans.dcim.module.job.api.dto.JobToggleRequest;
import net.vivans.dcim.module.job.application.JobService;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Collector Jobs")
@RequestMapping("/api")
public class JobController {

    private final JobService jobService;

    @PostMapping("/jobs/register")
    @Operation(summary = "수집 그룹 spec을 메모리 job으로 등록한다. 같은 groupId면 갱신한다.")
    public ApiResponse<JobResponse> register(@RequestBody CollectionGroupSpec spec) {
        return ApiResponse.ok(jobService.register(spec));
    }

    @PutMapping("/jobs/{collectorJobId}")
    @Operation(summary = "등록된 job의 spec을 교체하고 cron을 다시 건다.")
    public ApiResponse<JobResponse> update(
            @PathVariable String collectorJobId,
            @RequestBody CollectionGroupSpec spec
    ) {
        return ApiResponse.ok(jobService.update(collectorJobId, spec));
    }

    @DeleteMapping("/jobs/{collectorJobId}")
    @Operation(summary = "job을 삭제하고 cron을 해제한다.")
    public ApiResponse<Void> delete(@PathVariable String collectorJobId) {
        jobService.delete(collectorJobId);
        return ApiResponse.ok();
    }

    @PatchMapping("/jobs/{collectorJobId}/toggle")
    @Operation(summary = "job 활성/비활성. 비활성이면 cron만 멈춘다.")
    public ApiResponse<JobResponse> toggle(
            @PathVariable String collectorJobId,
            @Valid @RequestBody JobToggleRequest request
    ) {
        return ApiResponse.ok(jobService.toggle(collectorJobId, request));
    }

    @GetMapping("/jobs")
    @Operation(summary = "메모리에 올라간 job 목록")
    public ApiResponse<List<JobResponse>> list() {
        return ApiResponse.ok(jobService.list());
    }

    @GetMapping("/health")
    @Operation(summary = "컬렉터 생존 확인. DB가 없으므로 job 개수만 반환한다.")
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.ok(new HealthResponse("UP", jobService.count(), Map.of("storage", "memory")));
    }
}
