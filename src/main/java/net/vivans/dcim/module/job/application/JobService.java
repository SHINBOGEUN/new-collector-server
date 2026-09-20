package net.vivans.dcim.module.job.application;

import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.api.dto.JobResponse;
import net.vivans.dcim.module.job.api.dto.JobToggleRequest;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class JobService {

    public static final String LIVE_JOB_ID = "live";

    private final ThreadPoolTaskScheduler scheduler;
    private final CollectionTickRunner tickRunner;
    private final PueTickRunner tickRunnerPue;
    private final Map<String, RegisteredJob> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, String> jobIdByGroupId = new ConcurrentHashMap<>();
    /**
     * groupId 단위 register/update/delete를 원자적으로 만들기 위한 락.
     * 동시에 같은 groupId로 register()가 두 번 들어와도(TOCTOU) 중복 job이 생기지 않게 한다.
     * register 빈도가 낮은 관리 operation이라 단일 락으로도 충분하다(tick 실행 경로에는 영향 없음).
     */
    private final Object registrationLock = new Object();
    private final Map<Integer, PueJob> pueJobs = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicBoolean liveRunning = new AtomicBoolean(false);
    private final Object liveLock = new Object();
    private volatile LiveCollectionSpec liveSpec;
    private volatile ScheduledFuture<?> liveFuture;

    public JobService(
            @Qualifier("collectorTaskScheduler") ThreadPoolTaskScheduler scheduler,
            CollectionTickRunner tickRunner,
            PueTickRunner tickRunnerPue
    ) {
        this.scheduler = scheduler;
        this.tickRunner = tickRunner;
        this.tickRunnerPue = tickRunnerPue;
    }

    public JobResponse register(CollectionGroupSpec spec) {
        validate(spec);
        synchronized (registrationLock) {
            String existingId = jobIdByGroupId.get(spec.groupId());
            if (existingId != null && jobs.containsKey(existingId)) {
                return update(existingId, spec);
            }
            String jobId = UUID.randomUUID().toString();
            RegisteredJob job = new RegisteredJob(jobId, spec, true);
            jobs.put(jobId, job);
            jobIdByGroupId.put(spec.groupId(), jobId);
            schedule(job);
            log.info("[COLLECTOR_JOB_END] type=REGULAR action=REGISTER collectorJobId={} groupId={}", jobId, spec.groupId());
            return toResponse(job);
        }
    }

    public JobResponse update(String collectorJobId, CollectionGroupSpec spec) {
        validate(spec);
        synchronized (registrationLock) {
            RegisteredJob current = requireJob(collectorJobId);
            cancel(current);
            jobIdByGroupId.remove(current.spec().groupId());
            current.replaceSpec(spec);
            jobIdByGroupId.put(spec.groupId(), collectorJobId);
            if (current.enabled()) {
                schedule(current);
            }
            log.info("[COLLECTOR_JOB_END] type=REGULAR action=UPDATE collectorJobId={} groupId={}", collectorJobId, spec.groupId());
            return toResponse(current);
        }
    }

    public void delete(String collectorJobId) {
        synchronized (registrationLock) {
            RegisteredJob job = requireJob(collectorJobId);
            cancel(job);
            jobs.remove(collectorJobId);
            jobIdByGroupId.remove(job.spec().groupId());
            log.info("[COLLECTOR_JOB_END] type=REGULAR action=DELETE collectorJobId={}", collectorJobId);
        }
    }

    public JobResponse toggle(String collectorJobId, JobToggleRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("enabled 값이 필요합니다.");
        }
        RegisteredJob job = requireJob(collectorJobId);
        cancel(job);
        job.setEnabled(request.enabled());
        if (job.enabled()) {
            schedule(job);
        }
        log.info("[COLLECTOR_JOB_END] type=REGULAR action=TOGGLE collectorJobId={} enabled={}", collectorJobId, job.enabled());
        return toResponse(job);
    }

    public JobResponse upsertLive(LiveCollectionSpec spec) {
        validateLive(spec);
        synchronized (liveLock) {
            cancelLive();
            liveSpec = spec;
            scheduleLive();
        }
        log.info("live job 등록 targetCount={}", spec.targets() == null ? 0 : spec.targets().size());
        return liveResponse();
    }

    public void deleteLive() {
        synchronized (liveLock) {
            cancelLive();
            liveSpec = null;
        }
        log.info("live job 삭제");
    }

    public JobResponse getLive() {
        synchronized (liveLock) {
            if (liveSpec == null) {
                throw new NoSuchElementException("live job이 없습니다.");
            }
            return liveResponse();
        }
    }

    public List<JobResponse> list() {
        List<JobResponse> responses = new ArrayList<>();
        for (RegisteredJob job : jobs.values()) {
            responses.add(toResponse(job));
        }
        return responses;
    }

    public int count() {
        int live = liveSpec == null ? 0 : 1;
        return jobs.size() + pueJobs.size() + live;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void upsertPue(net.vivans.dcim.module.job.domain.PueCollectionSpec spec) {
        if (spec == null || spec.pueDefinitionId() == null || spec.cronExpression() == null || spec.sources() == null || spec.sources().isEmpty()) {
            throw new IllegalArgumentException("valid PUE spec is required");
        }
        log.info("[PUE_JOB_START] action=UPSERT definitionId={} sourceCount={} cron={}",
                spec.pueDefinitionId(), spec.sources().size(), spec.cronExpression());
        try {
            PueJob job = pueJobs.computeIfAbsent(spec.pueDefinitionId(), ignored -> new PueJob());
            if (job.future != null) {
                job.future.cancel(false);
            }
            job.spec = spec;
            job.future = scheduler.schedule(
                    () -> tickRunnerPue.run(job.spec, job.running),
                    new CronTrigger(spec.cronExpression(), ZoneId.systemDefault())
            );
            log.info("[PUE_JOB_END] action=UPSERT definitionId={} activePueJobCount={}",
                    spec.pueDefinitionId(), pueJobs.size());
        } catch (RuntimeException exception) {
            log.warn("[PUE_JOB_ERROR] action=UPSERT definitionId={} exception={} message={}",
                    spec.pueDefinitionId(), exception.getClass().getSimpleName(), exception.getMessage());
            throw exception;
        }
    }
    public void deletePue(Integer definitionId) {
        log.info("[PUE_JOB_START] action=DELETE definitionId={}", definitionId);
        PueJob job = pueJobs.remove(definitionId);
        if (job != null && job.future != null) {
            job.future.cancel(false);
        }
        log.info("[PUE_JOB_END] action=DELETE definitionId={} removed={}", definitionId, job != null);
    }

    private void schedule(RegisteredJob job) {
        CronTrigger trigger = new CronTrigger(job.spec().cronExpression(), ZoneId.systemDefault());
        ScheduledFuture<?> future = scheduler.schedule(
                () -> tickRunner.run(job.spec(), job.running(), job::recordTickResult),
                trigger
        );
        job.setFuture(future);
    }

    private void scheduleLive() {
        LiveCollectionSpec spec = liveSpec;
        if (spec == null) {
            return;
        }
        long intervalMs = Math.max(1L, spec.intervalMs());
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                () -> tickRunner.runLive(liveSpec, liveRunning),
                Instant.now(),
                Duration.ofMillis(intervalMs)
        );
        liveFuture = future;
    }

    private void cancelLive() {
        ScheduledFuture<?> future = liveFuture;
        if (future != null) {
            future.cancel(false);
            liveFuture = null;
        }
    }

    private void cancel(RegisteredJob job) {
        ScheduledFuture<?> future = job.future();
        if (future != null) {
            future.cancel(false);
            job.setFuture(null);
        }
    }

    private RegisteredJob requireJob(String collectorJobId) {
        RegisteredJob job = jobs.get(collectorJobId);
        if (job == null) {
            throw new NoSuchElementException("job을 찾을 수 없습니다: " + collectorJobId);
        }
        return job;
    }

    private void validate(CollectionGroupSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec이 필요합니다.");
        }
        if (spec.groupId() == null || spec.taskId() == null) {
            throw new IllegalArgumentException("taskId와 groupId가 필요합니다.");
        }
        if (spec.cronExpression() == null || spec.cronExpression().isBlank()) {
            throw new IllegalArgumentException("cronExpression이 필요합니다.");
        }
        try {
            new CronTrigger(spec.cronExpression(), ZoneId.systemDefault());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("cronExpression이 올바르지 않습니다: " + spec.cronExpression());
        }
    }

    private void validateLive(LiveCollectionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec이 필요합니다.");
        }
        if (spec.protocol() == null || spec.protocol().isBlank()) {
            throw new IllegalArgumentException("protocol이 필요합니다.");
        }
        if (spec.targets() == null || spec.targets().isEmpty()) {
            throw new IllegalArgumentException("targets가 필요합니다.");
        }
    }

    private JobResponse liveResponse() {
        LiveCollectionSpec spec = liveSpec;
        return new JobResponse(
                LIVE_JOB_ID,
                null,
                null,
                null,
                spec == null ? null : spec.protocol(),
                null,
                spec != null,
                spec == null || spec.targets() == null ? 0 : spec.targets().size(),
                null,
                null,
                0,
                null
        );
    }

    private JobResponse toResponse(RegisteredJob job) {
        CollectionGroupSpec spec = job.spec();
        return new JobResponse(
                job.collectorJobId(),
                spec.taskId(),
                spec.groupId(),
                spec.modelId(),
                spec.protocol(),
                spec.cronExpression(),
                job.enabled(),
                spec.targets() == null ? 0 : spec.targets().size(),
                job.lastSuccessAt(),
                job.lastFailureAt(),
                job.consecutiveFailureCount(),
                job.lastFailureReason()
        );
    }

    static final class RegisteredJob {
        private final String collectorJobId;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private volatile CollectionGroupSpec spec;
        private volatile boolean enabled;
        private volatile ScheduledFuture<?> future;
        private final AtomicInteger consecutiveFailureCount = new AtomicInteger(0);
        private volatile Instant lastFailureAt;
        private volatile String lastFailureReason;
        private volatile Instant lastSuccessAt;

        RegisteredJob(String collectorJobId, CollectionGroupSpec spec, boolean enabled) {
            this.collectorJobId = collectorJobId;
            this.spec = spec;
            this.enabled = enabled;
        }

        String collectorJobId() {
            return collectorJobId;
        }

        CollectionGroupSpec spec() {
            return spec;
        }

        boolean enabled() {
            return enabled;
        }

        AtomicBoolean running() {
            return running;
        }

        ScheduledFuture<?> future() {
            return future;
        }

        void replaceSpec(CollectionGroupSpec spec) {
            this.spec = spec;
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        /**
         * tick 결과를 반영한다. 이번 tick에서 하나라도 실패했으면 실패로 집계하고(부분 성공이어도
         * '완전히 정상'은 아니므로), 실패한 대상이 하나도 없을 때만 정상 복구로 보고 연속 실패
         * 횟수를 0으로 되돌린다. 별도의 '복구 처리' 로직 없이, 다음 정상 tick이 곧 자동 복구다.
         */
        void recordTickResult(CollectionTickSummary summary) {
            if (summary.failed() > 0) {
                consecutiveFailureCount.incrementAndGet();
                lastFailureAt = Instant.now();
                if (summary.lastFailureReason() != null) {
                    lastFailureReason = summary.lastFailureReason();
                }
            } else if (summary.success() > 0) {
                consecutiveFailureCount.set(0);
                lastSuccessAt = Instant.now();
            }
        }

        int consecutiveFailureCount() {
            return consecutiveFailureCount.get();
        }

        Instant lastFailureAt() {
            return lastFailureAt;
        }

        String lastFailureReason() {
            return lastFailureReason;
        }

        Instant lastSuccessAt() {
            return lastSuccessAt;
        }
    }
    static final class PueJob { private final AtomicBoolean running=new AtomicBoolean(false); private volatile net.vivans.dcim.module.job.domain.PueCollectionSpec spec; private volatile ScheduledFuture<?> future; }
}
