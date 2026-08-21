package net.vivans.dcim.module.job.application;

import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionPointSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionTargetSpec;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class CollectionTickRunner {

    private final SnmpQueryClient snmpQueryClient;
    private final MqttPublisher mqttPublisher;
    private final OidTemplateResolver oidTemplateResolver;
    private final CollectionMetrics collectionMetrics;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("collector-snmp-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<Integer, AtomicBoolean> liveTargetRunning = new ConcurrentHashMap<>();

    public CollectionTickRunner(
            SnmpQueryClient snmpQueryClient,
            MqttPublisher mqttPublisher,
            OidTemplateResolver oidTemplateResolver,
            CollectionMetrics collectionMetrics
    ) {
        this.snmpQueryClient = snmpQueryClient;
        this.mqttPublisher = mqttPublisher;
        this.oidTemplateResolver = oidTemplateResolver;
        this.collectionMetrics = collectionMetrics;
    }

    public void run(CollectionGroupSpec spec, AtomicBoolean running) {
        if (!running.compareAndSet(false, true)) {
            log.info("이전 tick이 아직 실행 중이라 건너뜁니다. groupId={}", spec.groupId());
            return;
        }
        try {
            collect(spec);
        } finally {
            running.set(false);
        }
    }

    public void runLive(LiveCollectionSpec spec, AtomicBoolean running) {
        collectLive(spec);
    }

    private void collect(CollectionGroupSpec spec) {
        if (spec.protocol() == null || !"snmp".equalsIgnoreCase(spec.protocol())) {
            log.debug("SNMP가 아닌 프로토콜은 실행하지 않습니다. groupId={} protocol={}", spec.groupId(), spec.protocol());
            return;
        }
        List<CollectionGroupTargetSpec> targets = spec.targets() == null ? List.of() : spec.targets();
        if (targets.isEmpty()) {
            log.debug("수집 대상이 없습니다. groupId={}", spec.groupId());
            return;
        }

        int concurrency = Math.max(spec.maxConcurrency(), 1);
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (CollectionGroupTargetSpec target : targets) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    if (collectTarget(spec, target)) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    failureCount.incrementAndGet();
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
        }

        logTickSummary(spec, targets.size(), successCount.get(), failureCount.get());
    }

    private boolean collectTarget(CollectionGroupSpec spec, CollectionGroupTargetSpec target) {
        try {
            List<SnmpQueryClient.OidQuery> queries = new ArrayList<>();
            for (CollectionGroupOidSpec oid : spec.oids() == null ? List.<CollectionGroupOidSpec>of() : spec.oids()) {
                queries.add(new SnmpQueryClient.OidQuery(oid.name(), oidTemplateResolver.resolve(oid, target)));
            }
            Map<String, Object> values = snmpQueryClient.get(
                    target.host(),
                    target.port(),
                    spec.community(),
                    spec.timeoutMs(),
                    spec.retries(),
                    queries
            );
            mqttPublisher.publishSensorReading(spec.taskId(), spec.groupId(), target.deviceId(), values);
            collectionMetrics.recordSuccess();
            return true;
        } catch (Exception ex) {
            collectionMetrics.recordFailure();
            log.warn(
                    "수집 실패 taskId={} groupId={} deviceId={} host={}:{} reason={}",
                    spec.taskId(),
                    spec.groupId(),
                    target.deviceId(),
                    target.host(),
                    target.port(),
                    ex.getMessage()
            );
            return false;
        }
    }

    private void logTickSummary(CollectionGroupSpec spec, int total, int success, int failed) {
        CollectionTickSummary summary = new CollectionTickSummary(total, success, failed);
        if (failed > 0) {
            log.warn(
                    "수집 tick 요약 taskId={} groupId={} total={} success={} failed={}",
                    spec.taskId(),
                    spec.groupId(),
                    summary.total(),
                    summary.success(),
                    summary.failed()
            );
            return;
        }
        log.info(
                "수집 tick 요약 taskId={} groupId={} total={} success={} failed={}",
                spec.taskId(),
                spec.groupId(),
                summary.total(),
                summary.success(),
                summary.failed()
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
                    collectLiveTarget(spec, target);
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

    private boolean collectLiveTarget(LiveCollectionSpec spec, LiveCollectionTargetSpec target) {
        try {
            List<LiveCollectionPointSpec> points = target.points() == null ? List.of() : target.points();
            if (points.isEmpty()) {
                return false;
            }
            CollectionGroupTargetSpec resolveTarget = new CollectionGroupTargetSpec(
                    target.deviceId(),
                    target.host(),
                    target.port(),
                    target.instanceId()
            );
            List<SnmpQueryClient.OidQuery> queries = new ArrayList<>();
            for (LiveCollectionPointSpec point : points) {
                CollectionGroupOidSpec oid = new CollectionGroupOidSpec(
                        point.name(),
                        point.template(),
                        point.requiresInstance()
                );
                queries.add(new SnmpQueryClient.OidQuery(point.name(), oidTemplateResolver.resolve(oid, resolveTarget)));
            }
            Map<String, Object> values = snmpQueryClient.get(
                    target.host(),
                    target.port(),
                    spec.community(),
                    spec.timeoutMs(),
                    spec.retries(),
                    queries
            );
            for (LiveCollectionPointSpec point : points) {
                Object value = values.get(point.name());
                if (value == null) {
                    continue;
                }
                mqttPublisher.publishLivePoint(
                        target.deviceId(),
                        target.deviceName(),
                        point.name(),
                        point.unit(),
                        value
                );
            }
            collectionMetrics.recordSuccess();
            return true;
        } catch (Exception ex) {
            collectionMetrics.recordFailure();
            log.warn(
                    "live 수집 실패 deviceId={} host={}:{} reason={}",
                    target.deviceId(),
                    target.host(),
                    target.port(),
                    ex.getMessage()
            );
            return false;
        }
    }
}
