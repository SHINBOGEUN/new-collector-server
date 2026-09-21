package net.vivans.dcim.module.job.application;

import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionTargetSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class CollectionTickRunner {

    private final SnmpCollectionRunner snmpCollectionRunner;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("collector-snmp-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<Integer, AtomicBoolean> liveTargetRunning = new ConcurrentHashMap<>();

    public CollectionTickRunner(SnmpCollectionRunner snmpCollectionRunner) {
        this.snmpCollectionRunner = snmpCollectionRunner;
    }

    public void run(CollectionGroupSpec spec, AtomicBoolean running) {
        run(spec, running, summary -> { });
    }

    /**
     * @param listener tick이 실제로 실행되어 완료되었을 때 결과를 통지받는다(등록된 job의
     *                  최근 실패 시각/횟수/원인을 추적하는 용도). SNMP가 아니거나 대상이 없어
     *                  건너뛴 tick에서는 호출되지 않는다.
     */
    public void run(CollectionGroupSpec spec, AtomicBoolean running, CollectionTickListener listener) {
        if (!running.compareAndSet(false, true)) {
            log.info("[COLLECT_SKIP] type=REGULAR taskId={} groupId={} reason=ALREADY_RUNNING",
                    spec.taskId(), spec.groupId());
            return;
        }
        long startedAt = System.nanoTime();
        int targetCount = spec.targets() == null ? 0 : spec.targets().size();
        log.info("[COLLECT_START] type=REGULAR taskId={} groupId={} targetCount={}",
                spec.taskId(), spec.groupId(), targetCount);
        try {
            CollectionTickSummary summary = collect(spec);
            log.info("[COLLECT_END] type=REGULAR taskId={} groupId={} targetCount={} elapsedMs={}",
                    spec.taskId(), spec.groupId(), targetCount, elapsedMillis(startedAt));
            if (summary != null) {
                listener.onTickCompleted(summary);
            }
        } finally {
            running.set(false);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public void runLive(LiveCollectionSpec spec, AtomicBoolean running) {
        collectLive(spec);
    }

    private CollectionTickSummary collect(CollectionGroupSpec spec) {
        if (spec.protocol() == null || !"snmp".equalsIgnoreCase(spec.protocol())) {
            log.debug("SNMP가 아닌 프로토콜은 실행하지 않습니다. groupId={} protocol={}", spec.groupId(), spec.protocol());
            return null;
        }
        List<CollectionGroupTargetSpec> targets = spec.targets() == null ? List.of() : spec.targets();
        if (targets.isEmpty()) {
            log.debug("수집 대상이 없습니다. groupId={}", spec.groupId());
            return null;
        }

        int concurrency = Math.max(spec.maxConcurrency(), 1);
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        AtomicReference<String> lastFailureReason = new AtomicReference<>();

        for (CollectionGroupTargetSpec target : targets) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    CollectionTargetResult result = snmpCollectionRunner.collectTarget(spec, target);
                    if (result.success()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                        lastFailureReason.set(result.reason());
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failureCount.incrementAndGet();
                    lastFailureReason.set("interrupted: " + ex.getMessage());
                } finally {
                    semaphore.release();
                }
            }, executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(Math.max(spec.timeoutMs(), 1) * 4L * targets.size(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn(
                    "그룹 tick 대기 중 오류 taskId={} groupId={}: {}",
                    spec.taskId(),
                    spec.groupId(),
                    ex.getMessage()
            );
            lastFailureReason.compareAndSet(null, "tick 대기 중 오류: " + ex.getMessage());
        }

        CollectionTickSummary summary = new CollectionTickSummary(
                targets.size(), successCount.get(), failureCount.get(), lastFailureReason.get());
        logTickSummary(spec, summary.total(), summary.success(), summary.failed());
        return summary;
    }

    private void logTickSummary(CollectionGroupSpec spec, int total, int success, int failed) {
        if (failed > 0) {
            log.warn(
                    "수집 tick 요약 taskId={} groupId={} total={} success={} failed={}",
                    spec.taskId(),
                    spec.groupId(),
                    total,
                    success,
                    failed
            );
            return;
        }
        log.info(
                "수집 tick 요약 taskId={} groupId={} total={} success={} failed={}",
                spec.taskId(),
                spec.groupId(),
                total,
                success,
                failed
        );
    }

    private void collectLive(LiveCollectionSpec spec) {
        if (spec.protocol() == null || !"snmp".equalsIgnoreCase(spec.protocol())) {
            log.debug("SNMP가 아닌 live 프로토콜은 실행하지 않습니다. protocol={}", spec.protocol());
            return;
        }
        List<LiveCollectionTargetSpec> targets = spec.targets() == null ? List.of() : spec.targets();
        if (targets.isEmpty()) {
            log.debug("live 수집 대상이 없습니다.");
            return;
        }

        int concurrency = Math.max(spec.maxConcurrency(), 1);
        Semaphore semaphore = new Semaphore(concurrency);

        for (LiveCollectionTargetSpec target : targets) {
            AtomicBoolean targetRunning = liveTargetRunning.computeIfAbsent(
                    target.deviceId(),
                    ignored -> new AtomicBoolean(false)
            );
            if (!targetRunning.compareAndSet(false, true)) {
                continue;
            }
            executor.execute(() -> {
                boolean acquired = false;
                try {
                    semaphore.acquire();
                    acquired = true;
                    snmpCollectionRunner.collectLiveTarget(spec, target);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    log.warn(
                            "live 수집 실패 deviceId={} host={}:{} reason={}",
                            target.deviceId(),
                            target.host(),
                            target.port(),
                            ex.getMessage()
                    );
                } finally {
                    if (acquired) {
                        semaphore.release();
                    }
                    targetRunning.set(false);
                }
            });
        }
    }


}
