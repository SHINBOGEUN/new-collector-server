package net.vivans.dcim.module.job.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.PueCollectionSourceSpec;
import net.vivans.dcim.module.job.domain.PueCollectionSpec;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class PueTickRunner {
    private final SnmpQueryClient snmp;
    private final MqttPublisher mqtt;

    public void run(PueCollectionSpec spec, AtomicBoolean running) {
        if (!running.compareAndSet(false, true)) {
            log.debug("[PUE_COLLECT_SKIP] definitionId={} reason=ALREADY_RUNNING", spec.pueDefinitionId());
            return;
        }
        long startedAt = System.nanoTime();
        log.info("[PUE_COLLECT_START] definitionId={} sourceCount={}", spec.pueDefinitionId(), spec.sources().size());
        try {
            List<CompletableFuture<Reading>> futures = spec.sources().stream()
                    .map(source -> CompletableFuture.supplyAsync(() -> read(spec, source))).toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(Math.max(1, spec.timeoutMs()) * 2L, TimeUnit.MILLISECONDS);
            double total = 0D;
            double cooler = 0D;
            for (CompletableFuture<Reading> future : futures) {
                Reading reading = future.join();
                if ("total".equals(reading.role())) total += reading.value();
                else if ("cooler".equals(reading.role())) cooler += reading.value();
            }
            if (cooler <= 0D) {
                log.warn("[PUE_COLLECT_SKIP] definitionId={} reason=ZERO_COOLER_POWER totalPower={} coolerPower={}",
                        spec.pueDefinitionId(), total, cooler);
                return;
            }
            mqtt.publishPueReading(spec.pueDefinitionId(), spec.configVersion() == null ? 1 : spec.configVersion(), total / cooler, total, cooler);
            log.info("[PUE_COLLECT_END] definitionId={} totalPower={} coolerPower={} value={} elapsedMs={}",
                    spec.pueDefinitionId(), total, cooler, total / cooler, elapsedMillis(startedAt));
        } catch (Exception exception) {
            log.warn("[PUE_COLLECT_ERROR] definitionId={} elapsedMs={} exception={} message={}",
                    spec.pueDefinitionId(), elapsedMillis(startedAt), exception.getClass().getSimpleName(), exception.getMessage());
        } finally { running.set(false); }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private Reading read(PueCollectionSpec spec, PueCollectionSourceSpec source) {
        try {
            Map<String, Object> values = snmp.get(source.host(), source.port(), spec.community(), spec.timeoutMs(), spec.retries(),
                    List.of(new SnmpQueryClient.OidQuery(source.pointName(), source.oid())));
            Object raw = values.get(source.pointName());
            if (!(raw instanceof Number number)) throw new IllegalStateException("missing PUE source " + source.deviceId());
            return new Reading(source.role(), number.doubleValue() * (source.scale() == null ? 1D : source.scale()));
        } catch (Exception e) { throw new CompletionException(e); }
    }
    private record Reading(String role, double value) { }
}
