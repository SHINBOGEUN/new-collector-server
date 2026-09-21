package net.vivans.dcim.module.job.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.vivans.dcim.module.job.api.dto.JobResponse;
import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수집 안정성 강화 작업 검증:
 * - JobService.register()가 같은 groupId에 대해 동시에 여러 번 호출돼도 job이 하나만 생긴다(중복 등록 방지).
 * - job 실행 결과(실패/복구)가 GET /api/jobs 응답으로 노출되는 필드(JobResponse)에 반영된다.
 */
class JobServiceTest {

    private ThreadPoolTaskScheduler scheduler;

    private JobService newJobService(SnmpQueryClient snmp, MqttPublisher mqtt) {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("job-service-test-");
        scheduler.initialize();
        CollectionTickRunner tickRunner = new CollectionTickRunner(new SnmpCollectionRunner(
                snmp, mqtt, new OidTemplateResolver(), new CollectionMetrics(new SimpleMeterRegistry())));
        PueTickRunner pueTickRunner = new PueTickRunner(snmp, mqtt);
        return new JobService(scheduler, tickRunner, pueTickRunner);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void concurrentRegisterForSameGroupCreatesExactlyOneJob() throws Exception {
        SnmpQueryClient snmp = (host, port, community, timeoutMs, retries, oids) -> Map.of("V", 1);
        MqttPublisher mqtt = (taskId, groupId, deviceId, values) -> { };
        JobService jobService = newJobService(snmp, mqtt);

        CollectionGroupSpec spec = spec("0 0 0 1 1 *"); // 매년 1월 1일: 이 테스트에서는 실행되지 않아도 됨
        int attempts = 20;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(2, TimeUnit.SECONDS);
                        jobService.register(spec);
                    } catch (Exception ex) {
                        failures.incrementAndGet();
                    }
                });
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures.get()).isZero();
        List<JobResponse> jobs = jobService.list();
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).groupId()).isEqualTo(spec.groupId());
    }

    @Test
    void failureIsVisibleAndClearsAutomaticallyOnNextSuccess() throws Exception {
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        CountDownLatch firstTick = new CountDownLatch(1);
        CountDownLatch secondTick = new CountDownLatch(2);
        SnmpQueryClient snmp = (host, port, community, timeoutMs, retries, oids) -> {
            try {
                if (shouldFail.get()) {
                    throw new IllegalStateException("simulated snmp timeout");
                }
                return Map.of("V", 1);
            } finally {
                firstTick.countDown();
                secondTick.countDown();
            }
        };
        MqttPublisher mqtt = (taskId, groupId, deviceId, values) -> { };
        JobService jobService = newJobService(snmp, mqtt);

        CollectionGroupSpec spec = spec("*/1 * * * * *"); // 매초 실행
        JobResponse registered = jobService.register(spec);

        assertThat(firstTick.await(3, TimeUnit.SECONDS)).isTrue();
        JobResponse afterFailure = waitUntil(jobService, registered.collectorJobId(),
                job -> job.consecutiveFailureCount() > 0);
        assertThat(afterFailure.consecutiveFailureCount()).isGreaterThan(0);
        assertThat(afterFailure.lastFailureAt()).isNotNull();
        assertThat(afterFailure.lastFailureReason()).contains("simulated snmp timeout");

        shouldFail.set(false);
        assertThat(secondTick.await(3, TimeUnit.SECONDS)).isTrue();
        JobResponse afterRecovery = waitUntil(jobService, registered.collectorJobId(),
                job -> job.consecutiveFailureCount() == 0 && job.lastSuccessAt() != null);
        assertThat(afterRecovery.consecutiveFailureCount()).isZero();
        assertThat(afterRecovery.lastSuccessAt()).isNotNull();
        // 과거 실패 시각/사유는 이력으로 남아 있고, 연속 실패 횟수만 정상으로 복구된다.
        assertThat(afterRecovery.lastFailureAt()).isNotNull();
    }

    private JobResponse waitUntil(
            JobService jobService,
            String collectorJobId,
            java.util.function.Predicate<JobResponse> condition
    ) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        JobResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = jobService.list().stream()
                    .filter(job -> job.collectorJobId().equals(collectorJobId))
                    .findFirst()
                    .orElse(null);
            if (last != null && condition.test(last)) {
                return last;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condition not met in time, last=" + last);
    }

    private CollectionGroupSpec spec(String cron) {
        return new CollectionGroupSpec(
                1,
                11,
                10,
                "snmp",
                cron,
                "public",
                500,
                1,
                10,
                List.of(new CollectionGroupOidSpec("V", "1.3.6.1.4.1.6375.1.1.0", false, null)),
                List.of(new CollectionGroupTargetSpec(3, "192.168.14.114", 161, null)),
                List.of()
        );
    }
}
