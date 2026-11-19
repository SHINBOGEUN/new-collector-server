package net.vivans.dcim.module.job.application;

import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusPointResolver;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionGroupSpec;
import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionSpec;
import net.vivans.dcim.module.job.domain.LiveCollectionTargetSpec;
import net.vivans.dcim.module.job.domain.LiveModbusTargetSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

@Slf4j
@Component
public class CollectionTickRunner {

    private final SnmpCollectionRunner snmpCollectionRunner;
    private final ModbusCollectionRunner modbusCollectionRunner;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("collector-regular-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, AtomicBoolean> liveTargetRunning = new ConcurrentHashMap<>();

    public CollectionTickRunner(SnmpCollectionRunner snmpCollectionRunner,
                                ModbusCollectionRunner modbusCollectionRunner) {
        this.snmpCollectionRunner = snmpCollectionRunner;
        this.modbusCollectionRunner = modbusCollectionRunner;
    }

    public void run(CollectionGroupSpec spec, AtomicBoolean running) {
        run(spec, running, summary -> {});
    }

    /** 기존 직접 호출은 이번 tick 완료까지 기다린다. 실제 cron은 dispatch를 사용한다. */
    public void run(CollectionGroupSpec spec, AtomicBoolean running, CollectionTickListener listener) {
        if (!running.compareAndSet(false, true)) return;
        try {
            CompletableFuture<CollectionTickSummary> done=dispatchInternal(new CollectionJobRuntime("compat",spec),listener);
            int count=spec.targets()==null?0:spec.targets().size();
            long requests=1;
            if(spec instanceof ModbusCollectionGroupSpec m && m.targets()!=null)
                for(var target:m.targets())requests=Math.max(requests,ModbusPointResolver.resolve(m,target).size());
            long wait=Math.min(Math.max(1,spec.timeoutMs())*4L*Math.max(1,spec.retries()+1L)*requests*Math.max(1,count),TimeUnit.HOURS.toMillis(1));
            done.get(wait,TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {Thread.currentThread().interrupt();}
        catch (Exception e) {log.warn("regular compatibility tick wait failed groupId={}",spec.groupId());}
        finally {running.set(false);}
    }

    /** cron 스레드는 배분 구간만 보호한다. 다른 장비 완료를 기다리지 않는다. */
    void dispatch(CollectionJobRuntime runtime,AtomicBoolean running,CollectionTickListener listener) {
        if(!running.compareAndSet(false,true))return;
        try {dispatchInternal(runtime,listener);} finally {running.set(false);}
    }

    private CompletableFuture<CollectionTickSummary> dispatchInternal(CollectionJobRuntime runtime,CollectionTickListener listener) {
        var snapshot=runtime.snapshot();CollectionGroupSpec spec=snapshot.spec();
        if(!snapshot.enabled())return CompletableFuture.completedFuture(null);
        record Attempt(CollectionJobRuntime.DeviceRuntime device,List<net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec> targets) {}
        List<Attempt> attempts=new ArrayList<>();int skippedBackoff=0,skippedOverrun=0;
        for(var entry:CollectionJobRuntime.bySource(spec).entrySet()) {
            var state=runtime.source(entry.getKey(),snapshot.generation());if(state==null)continue;
            var claim=state.claim(snapshot,Instant.now());
            if(claim==CollectionJobRuntime.Claim.READY)attempts.add(new Attempt(state,entry.getValue()));
            else if(claim==CollectionJobRuntime.Claim.BACKOFF)skippedBackoff++;else skippedOverrun++;
        }
        int total=attempts.stream().mapToInt(a->a.targets().size()).sum();
        if(total==0)return CompletableFuture.completedFuture(null);
        Tick tick=new Tick(runtime,snapshot,total,skippedBackoff,skippedOverrun,listener);
        log.info("[COLLECT_START] type=REGULAR taskId={} groupId={} dispatched={} skippedBackoff={} skippedOverrun={}",spec.taskId(),spec.groupId(),total,skippedBackoff,skippedOverrun);
        for(Attempt attempt:attempts){
            DeviceBatch batch=new DeviceBatch(runtime,snapshot,attempt.device(),attempt.targets().size(),tick);
            for(var target:attempt.targets()) {
                Runnable work=()->{
                    if(!runtime.current(snapshot.generation())){batch.finish(null);return;}
                    batch.device.start();CollectionTargetResult result;
                    try {
                        if(spec instanceof SnmpCollectionGroupSpec snmp && target instanceof SnmpCollectionTargetSpec t)result=snmpCollectionRunner.collectTarget(snmp,t);
                        else if(spec instanceof ModbusCollectionGroupSpec modbus && target instanceof ModbusCollectionTargetSpec t)result=modbusCollectionRunner.collectTarget(modbus,t);
                        else result=new CollectionTargetResult(false,"unsupported collection target",0,"ERROR");
                        if(result==null)result=new CollectionTargetResult(false,"missing target result",0,"ERROR");
                    }catch(Exception e){result=new CollectionTargetResult(false,e.getClass().getSimpleName(),0,CollectionFailureClassifier.classify(e));}
                    batch.finish(result);
                };
                runtime.queue.add(new CollectionJobRuntime.Work(work,()->batch.finish(new CollectionTargetResult(false,"executor unavailable",0,"ERROR"))));
            }
        }
        drain(runtime);return tick.done;
    }

    private void drain(CollectionJobRuntime runtime) {
        if(!runtime.draining.compareAndSet(false,true))return;
        try {
            while(runtime.activeTargets.get()<runtime.concurrency()) {
                var work=runtime.queue.poll();if(work==null)break;
                runtime.activeTargets.incrementAndGet();
                try {
                    executor.execute(()->{
                        try{work.execute().run();}
                        finally{runtime.activeTargets.decrementAndGet();drain(runtime);}
                    });
                }catch(java.util.concurrent.RejectedExecutionException e){runtime.activeTargets.decrementAndGet();work.rejected().run();}
            }
        } finally {
            runtime.draining.set(false);
            if(!runtime.queue.isEmpty() && runtime.activeTargets.get()<runtime.concurrency())drain(runtime);
        }
    }

    private static final class DeviceBatch {
        final CollectionJobRuntime runtime;
        final CollectionJobRuntime.Snapshot snapshot;
        final CollectionJobRuntime.DeviceRuntime device;
        final AtomicInteger remaining,values=new AtomicInteger(),failed=new AtomicInteger();
        final AtomicReference<String> code=new AtomicReference<>(),reason=new AtomicReference<>();
        final Tick tick;
        DeviceBatch(CollectionJobRuntime runtime,CollectionJobRuntime.Snapshot snapshot,CollectionJobRuntime.DeviceRuntime device,int targets,Tick tick){
            this.runtime=runtime;this.snapshot=snapshot;this.device=device;this.remaining=new AtomicInteger(targets);this.tick=tick;
        }
        void finish(CollectionTargetResult result){
            if(result!=null){
                values.addAndGet(Math.max(0,result.valueCount()));
                if(!result.success()){failed.incrementAndGet();code.compareAndSet(null,result.failureCode());reason.compareAndSet(null,result.reason());}
            }
            if(remaining.decrementAndGet()==0)runtime.complete(device,snapshot,values.get(),failed.get(),code.get(),reason.get());
            tick.finish(result);
        }
    }

    private static final class Tick {
        final CollectionJobRuntime runtime;
        final CollectionJobRuntime.Snapshot snapshot;
        final int total,skippedBackoff,skippedOverrun;
        final AtomicInteger remaining,success=new AtomicInteger(),failed=new AtomicInteger();
        final AtomicReference<String> reason=new AtomicReference<>();
        final long started=System.nanoTime();
        final CollectionTickListener listener;
        final CompletableFuture<CollectionTickSummary> done=new CompletableFuture<>();
        Tick(CollectionJobRuntime runtime,CollectionJobRuntime.Snapshot snapshot,int total,int skippedBackoff,int skippedOverrun,CollectionTickListener listener){
            this.runtime=runtime;this.snapshot=snapshot;this.total=total;this.remaining=new AtomicInteger(total);this.skippedBackoff=skippedBackoff;this.skippedOverrun=skippedOverrun;this.listener=listener;
        }
        void finish(CollectionTargetResult result){
            if(result!=null){if(result.success())success.incrementAndGet();else{failed.incrementAndGet();reason.set(result.reason());}}
            if(remaining.decrementAndGet()!=0)return;
            CollectionTickSummary summary=new CollectionTickSummary(total,success.get(),failed.get(),reason.get());
            try {
                runtime.ifCurrent(snapshot.generation(),()->listener.onTickCompleted(summary));
                log.info("[COLLECT_END] type=REGULAR taskId={} groupId={} dispatched={} skippedBackoff={} skippedOverrun={} elapsedMs={}",snapshot.spec().taskId(),snapshot.spec().groupId(),total,skippedBackoff,skippedOverrun,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            } finally {done.complete(summary);}
        }
    }

    public void runLive(LiveCollectionSpec spec,AtomicBoolean running){collectLive(spec);}

    private void collectLive(LiveCollectionSpec spec) {
        if (spec == null || spec.protocol() == null
                || !("snmp".equalsIgnoreCase(spec.protocol()) || "modbus".equalsIgnoreCase(spec.protocol())
                || "mixed".equalsIgnoreCase(spec.protocol()))) {
            log.debug("지원하지 않는 live 프로토콜입니다. protocol={}", spec == null ? null : spec.protocol());
            return;
        }
        List<LiveCollectionTargetSpec> targets = spec.targets() == null ? List.of() : spec.targets();
        List<LiveModbusTargetSpec> modbusTargets = spec.modbusTargets() == null ? List.of() : spec.modbusTargets();
        if (targets.isEmpty() && modbusTargets.isEmpty()) {
            log.debug("live 수집 대상이 없습니다.");
            return;
        }

        int concurrency = Math.max(spec.maxConcurrency(), 1);
        Semaphore semaphore = new Semaphore(concurrency);

        for (LiveCollectionTargetSpec target : targets) {
            scheduleLiveTarget("snmp:" + target.deviceId() + ":" + target.host() + ":" + target.port(),
                    semaphore, target.host(), target.port(), target.deviceId(), () -> {
                    snmpCollectionRunner.collectLiveTarget(spec, target);
            });
        }
        for (LiveModbusTargetSpec liveTarget : modbusTargets) {
            ModbusCollectionTargetSpec target = liveTarget.target();
            scheduleLiveTarget("modbus:" + liveTarget.sourceDeviceId() + ":" + target.deviceId()
                            + ":" + target.host() + ":" + target.port() + ":" + target.unitId(),
                    semaphore, target.host(), target.port(), target.deviceId(), () -> {
                        modbusCollectionRunner.collectLiveTarget(spec, liveTarget);
                    });
        }
    }

    private void scheduleLiveTarget(String key, Semaphore semaphore, String host, int port,
                                    Integer deviceId, Runnable collect) {
        AtomicBoolean targetRunning = liveTargetRunning.computeIfAbsent(key, ignored -> new AtomicBoolean(false));
        if (!targetRunning.compareAndSet(false, true)) return;
        executor.execute(() -> {
            boolean acquired = false;
            try {
                semaphore.acquire();
                acquired = true;
                collect.run();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                log.warn("live 수집 실패 deviceId={} host={}:{} reason={}",
                        deviceId, host, port, ex.getMessage());
            } finally {
                if (acquired) semaphore.release();
                targetRunning.set(false);
            }
        });
    }

}
