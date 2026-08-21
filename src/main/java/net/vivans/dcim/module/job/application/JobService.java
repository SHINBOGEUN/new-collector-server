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

@Slf4j
@Service
public class JobService {

    public static final String LIVE_JOB_ID = "live";

    private final ThreadPoolTaskScheduler scheduler;
    private final CollectionTickRunner tickRunner;
    private final Map<String, RegisteredJob> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, String> jobIdByGroupId = new ConcurrentHashMap<>();
    private final AtomicBoolean liveRunning = new AtomicBoolean(false);
    private final Object liveLock = new Object();
    private volatile LiveCollectionSpec liveSpec;
    private volatile ScheduledFuture<?> liveFuture;

    public JobService(
            @Qualifier("collectorTaskScheduler") ThreadPoolTaskScheduler scheduler,
            CollectionTickRunner tickRunner
    ) {
        this.scheduler = scheduler;
        this.tickRunner = tickRunner;
    }

    public JobResponse register(CollectionGroupSpec spec) {
        validate(spec);
        String existingId = jobIdByGroupId.get(spec.groupId());
        if (existingId != null && jobs.containsKey(existingId)) {
            return update(existingId, spec);
        }
        String jobId = UUID.randomUUID().toString();
        RegisteredJob job = new RegisteredJob(jobId, spec, true);
        jobs.put(jobId, job);
        jobIdByGroupId.put(spec.groupId(), jobId);
        schedule(job);
        log.info("job 등록 collectorJobId={} groupId={}", jobId, spec.groupId());
        return toResponse(job);
    }

    public JobResponse update(String collectorJobId, CollectionGroupSpec spec) {
        validate(spec);
        RegisteredJob current = requireJob(collectorJobId);
        cancel(current);
        jobIdByGroupId.remove(current.spec().groupId());
        current.replaceSpec(spec);
        jobIdByGroupId.put(spec.groupId(), collectorJobId);
        if (current.enabled()) {
            schedule(current);
        }
        log.info("job 갱신 collectorJobId={} groupId={}", collectorJobId, spec.groupId());
        return toResponse(current);
    }

    public void delete(String collectorJobId) {
        RegisteredJob job = requireJob(collectorJobId);
        cancel(job);
        jobs.remove(collectorJobId);
        jobIdByGroupId.remove(job.spec().groupId());
        log.info("job 삭제 collectorJobId={}", collectorJobId);
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
        log.info("job toggle collectorJobId={} enabled={}", collectorJobId, job.enabled());
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
        return jobs.size() + live;
    }

    private void schedule(RegisteredJob job) {
        CronTrigger trigger = new CronTrigger(job.spec().cronExpression(), ZoneId.systemDefault());
        ScheduledFuture<?> future = scheduler.schedule(
                () -> tickRunner.run(job.spec(), job.running()),
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
                spec == null || spec.targets() == null ? 0 : spec.targets().size()
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
                spec.targets() == null ? 0 : spec.targets().size()
        );
    }

    static final class RegisteredJob {
        private final String collectorJobId;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private volatile CollectionGroupSpec spec;
        private volatile boolean enabled;
        private volatile ScheduledFuture<?> future;

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
    }
}
