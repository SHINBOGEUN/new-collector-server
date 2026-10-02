package net.vivans.dcim.module.job.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.PueCollectionSourceSpec;
import net.vivans.dcim.module.job.domain.PueCollectionSpec;
import net.vivans.dcim.module.job.domain.FormulaExpression;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.modbus.ModbusQueryClient;
import net.vivans.dcim.module.mqtt.MqttPublisher;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class PueTickRunner {
    private final SnmpQueryClient snmp;
    private final ModbusQueryClient modbus;
    private final MqttPublisher mqtt;

    public void run(PueCollectionSpec spec, AtomicBoolean running) {
        run(spec, running, (success, reason) -> {});
    }

    public void run(PueCollectionSpec spec, AtomicBoolean running, BiConsumer<Boolean, String> onComplete) {
        if (!running.compareAndSet(false, true)) {
            log.debug("[CALC_COLLECT_SKIP] definitionId={} reason=ALREADY_RUNNING", spec.pueDefinitionId());
            return;
        }
        long startedAt = System.nanoTime();
        boolean success = false;
        String failureReason = null;
        log.info("[CALC_COLLECT_START] definitionId={} sourceCount={} formula={}",
                spec.pueDefinitionId(), spec.sources().size(), spec.formula() != null);
        try {
            List<CompletableFuture<Reading>> futures = spec.sources().stream()
                    .map(source -> CompletableFuture.supplyAsync(() -> read(spec, source))).toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(Math.max(1, spec.timeoutMs()) * Math.max(2L, (long) spec.retries() + 1L)
                            * Math.max(1L, spec.sources().size()), TimeUnit.MILLISECONDS);
            if (spec.formula() != null && !spec.formula().isBlank()) {
                Map<String, Double> inputs = new LinkedHashMap<>();
                for (int i = 0; i < futures.size(); i++) {
                    String alias = spec.sources().get(i).alias();
                    if (alias == null || alias.isBlank() || inputs.putIfAbsent(alias, futures.get(i).join().value()) != null) {
                        throw new IllegalArgumentException("formula source aliases must be unique and nonblank");
                    }
                }
                double calculated = FormulaExpression.evaluate(spec.formula(), inputs);
                mqtt.publishCalculatedReading(spec.pueDefinitionId(), spec.configVersion() == null ? 1 : spec.configVersion(),
                        calculated, inputs);
                log.info("[CALCULATED_COLLECT_END] definitionId={} value={} sourceCount={} elapsedMs={}",
                        spec.pueDefinitionId(), calculated, inputs.size(), elapsedMillis(startedAt));
                success = true;
                return;
            }
            double total = 0D;
            double cooler = 0D;
            for (CompletableFuture<Reading> future : futures) {
                Reading reading = future.join();
                if ("total".equals(reading.role())) total += reading.value();
                else if ("cooler".equals(reading.role())) cooler += reading.value();
            }
            if (cooler <= 0D) {
                failureReason = "cooler power is zero or negative";
                log.warn("[PUE_COLLECT_SKIP] definitionId={} reason=ZERO_COOLER_POWER totalPower={} coolerPower={}",
                        spec.pueDefinitionId(), total, cooler);
                return;
            }
            mqtt.publishPueReading(spec.pueDefinitionId(), spec.configVersion() == null ? 1 : spec.configVersion(), total / cooler, total, cooler);
            log.info("[PUE_COLLECT_END] definitionId={} totalPower={} coolerPower={} value={} elapsedMs={}",
                    spec.pueDefinitionId(), total, cooler, total / cooler, elapsedMillis(startedAt));
            success = true;
        } catch (Exception exception) {
            Throwable cause = exception instanceof CompletionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            failureReason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            log.warn("[CALC_COLLECT_ERROR] definitionId={} elapsedMs={} exception={} message={}",
                    spec.pueDefinitionId(), elapsedMillis(startedAt), exception.getClass().getSimpleName(), exception.getMessage());
        } finally {
            running.set(false);
            onComplete.accept(success, failureReason);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private Reading read(PueCollectionSpec spec, PueCollectionSourceSpec source) {
        try {
            if ("modbus".equalsIgnoreCase(source.protocol())) {
                if (source.unitId() == null || source.modbusPoint() == null) {
                    throw new IllegalArgumentException("Modbus formula source requires unitId and point");
                }
                Map<String, Object> values = modbus.read(
                        new ModbusCollectionTargetSpec(source.deviceId(), source.host(), source.port(), source.unitId(),
                                List.of(source.modbusPoint())), List.of(source.modbusPoint()), spec.timeoutMs(), spec.retries());
                Object raw = values.get(source.pointName());
                if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw new IllegalStateException("missing Modbus formula source " + source.deviceId());
                }
                return new Reading(source.role(), number.doubleValue());
            }
            if (!"snmp".equalsIgnoreCase(source.protocol())) {
                throw new IllegalArgumentException("formula supports only SNMP and Modbus");
            }
            Map<String, Object> values = snmp.get(source.host(), source.port(), spec.community(), spec.timeoutMs(), spec.retries(),
                    List.of(new SnmpQueryClient.OidQuery(source.pointName(), source.oid())));
            Object raw = values.get(source.pointName());
            if (!(raw instanceof Number number)) throw new IllegalStateException("missing PUE source " + source.deviceId());
            double value = number.doubleValue() * (source.scale() == null ? 1D : source.scale());
            if (!Double.isFinite(value)) throw new IllegalStateException("non-finite formula source " + source.deviceId());
            return new Reading(source.role(), value);
        } catch (Exception e) { throw new CompletionException(e); }
    }
    private record Reading(String role, double value) { }
}
