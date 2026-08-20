package net.vivans.dcim.module.job.application;

import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class CollectionTickRunner {

    private final SnmpQueryClient snmpQueryClient;
    private final MqttPublisher mqttPublisher;
    private final OidTemplateResolver oidTemplateResolver;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("collector-snmp-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });

    public CollectionTickRunner(
            SnmpQueryClient snmpQueryClient,
            MqttPublisher mqttPublisher,
            OidTemplateResolver oidTemplateResolver
    ) {
        this.snmpQueryClient = snmpQueryClient;
        this.mqttPublisher = mqttPublisher;
        this.oidTemplateResolver = oidTemplateResolver;
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

        for (CollectionGroupTargetSpec target : targets) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    semaphore.acquire();
                    collectTarget(spec, target);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    semaphore.release();
                }
            }, executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(Math.max(spec.timeoutMs(), 1) * 4L * targets.size(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            log.warn("그룹 tick 대기 중 오류 groupId={}: {}", spec.groupId(), ex.getMessage());
        }
    }

    private void collectTarget(CollectionGroupSpec spec, CollectionGroupTargetSpec target) {
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
        } catch (Exception ex) {
            log.warn("장비 수집 실패 deviceId={} host={}: {}", target.deviceId(), target.host(), ex.getMessage());
        }
    }
}
