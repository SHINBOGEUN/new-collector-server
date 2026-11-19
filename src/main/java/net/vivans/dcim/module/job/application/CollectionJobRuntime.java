package net.vivans.dcim.module.job.application;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import net.vivans.dcim.module.job.api.dto.DeviceResultResponse;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;

/** 정기 job만 사용하는 메모리 상태. 대기 중인 대상은 실행 스레드를 점유하지 않는다. */
@Slf4j
final class CollectionJobRuntime {
    final String jobId;
    record Work(Runnable execute,Runnable rejected) {}
    enum Claim { READY, BACKOFF, OVERRUN }
    final ConcurrentLinkedQueue<Work> queue=new ConcurrentLinkedQueue<>();
    final AtomicInteger activeTargets=new AtomicInteger();
    final AtomicBoolean draining=new AtomicBoolean();
    private final Map<Integer,DeviceRuntime> devices=new ConcurrentHashMap<>();
    private CollectionGroupSpec spec;
    private long generation;
    private boolean enabled=true,retired;
    private long intervalSeconds;
    record Snapshot(CollectionGroupSpec spec,long generation,boolean enabled,long intervalSeconds,int threshold,int maxBackoff) {}
    CollectionJobRuntime(String jobId,CollectionGroupSpec spec){this.jobId=jobId;replace(spec);}
    static int sourceId(CollectionGroupTargetSpec target){
        return target instanceof ModbusCollectionTargetSpec m && m.sourceDeviceId()!=null?m.sourceDeviceId():target.deviceId();
    }
    static Map<Integer,List<CollectionGroupTargetSpec>> bySource(CollectionGroupSpec spec){
        Map<Integer,List<CollectionGroupTargetSpec>> grouped=new LinkedHashMap<>();
        if(spec.targets()!=null)for(CollectionGroupTargetSpec target:spec.targets())grouped.computeIfAbsent(sourceId(target),ignored->new ArrayList<>()).add(target);
        return grouped;
    }
    synchronized void replace(CollectionGroupSpec next){
        spec=next;generation++;
        try{CronExpression cron=CronExpression.parse(next.cronExpression());ZonedDateTime a=cron.next(ZonedDateTime.now(ZoneOffset.UTC)),b=a==null?null:cron.next(a);intervalSeconds=a==null || b==null?60:Math.max(1,Duration.between(a,b).getSeconds());}
        catch(RuntimeException e){intervalSeconds=60;}
        Map<Integer,List<CollectionGroupTargetSpec>> sources=bySource(next);
        devices.keySet().retainAll(sources.keySet());
        sources.forEach((id,targets)->{
            DeviceRuntime state=devices.computeIfAbsent(id,ignored->new DeviceRuntime(id));
            synchronized(state){state.targetCount=targets.size();state.backoffUntil=null;state.lastBackoffDelay=null;}
        });
    }
    synchronized void setEnabled(boolean value){enabled=value;if(!value)generation++;}
    synchronized void retire(){retired=true;enabled=false;generation++;devices.clear();}
    synchronized Snapshot snapshot(){return new Snapshot(spec,generation,enabled && !retired,intervalSeconds,spec.failureThreshold()==null?3:spec.failureThreshold(),spec.backoffMaxSeconds()==null?600:spec.backoffMaxSeconds());}
    synchronized boolean current(long epoch){return !retired && enabled && generation==epoch;}
    synchronized DeviceRuntime source(Integer id,long epoch){return current(epoch)?devices.get(id):null;}
    synchronized void complete(DeviceRuntime state,Snapshot snapshot,int values,int failed,String code,String reason){
        state.complete(snapshot,values,failed,code,reason,current(snapshot.generation()) && devices.get(state.sourceId)==state);
    }
    synchronized void ifCurrent(long epoch,Runnable work){if(current(epoch))work.run();}
    synchronized int concurrency(){return Math.max(1,spec.maxConcurrency());}
    List<DeviceResultResponse> results(){
        Snapshot current=snapshot();List<DeviceResultResponse> result=new ArrayList<>();
        bySource(current.spec()).forEach((id,targets)->{
            DeviceRuntime state=devices.get(id);if(state!=null)result.add(state.response(jobId,current,id));
        });return result;
    }
    final class DeviceRuntime {
        final Integer sourceId;
        private Long lastBackoffDelay;
        DeviceRuntime(Integer id){sourceId=id;}
        private boolean busy;
        private Instant claimedAt,inFlightSince,lastAttemptAt,lastSuccessAt,lastFailureAt,backoffUntil;
        private String lastOutcome,lastFailureCode,lastFailureReason;
        private int failures,targetCount,failedTargets,skippedBackoff,skippedOverrun;
        synchronized Claim claim(Snapshot snapshot,Instant now){
            if(busy){
                skippedOverrun++;
                if(inFlightSince!=null && now.isAfter(inFlightSince.plusSeconds(snapshot.intervalSeconds()*2)))
                    record(snapshot,now,0,Math.max(targetCount,1),"OVERRUN","previous device attempt is still running");
                return Claim.OVERRUN;
            }
            if(backoffUntil!=null && now.plusSeconds(1).isBefore(backoffUntil)){skippedBackoff++;return Claim.BACKOFF;}
            busy=true;claimedAt=now;return Claim.READY;
        }
        synchronized void start(){
            if(inFlightSince==null){inFlightSince=Instant.now();lastAttemptAt=inFlightSince;skippedBackoff=0;skippedOverrun=0;}
        }
        synchronized void complete(Snapshot snapshot,int values,int failed,String code,String reason,boolean valid){
            try{if(valid)record(snapshot,Instant.now(),values,failed,code,reason);}
            finally{busy=false;inFlightSince=null;}
        }
        private void record(Snapshot snapshot,Instant now,int values,int failed,String code,String reason){
            int before=failures;failedTargets=failed;
            if(failed==0 || values>0){
                lastOutcome=failed==0?"SUCCESS":"PARTIAL";lastSuccessAt=now;failures=0;backoffUntil=null;lastBackoffDelay=null;
                if(before>0)log.info("[COLLECT_DEVICE_RECOVERED] jobId={} groupId={} deviceId={} afterFailures={}",jobId,snapshot.spec().groupId(),sourceId,before);
            }else{lastOutcome="FAILED";failures=failures==Integer.MAX_VALUE?failures:failures+1;}
            if(failed>0){lastFailureAt=now;lastFailureCode=code==null?"ERROR":code;lastFailureReason=reason;}
            if("FAILED".equals(lastOutcome) && failures>=snapshot.threshold() && snapshot.maxBackoff()>0){
                long multiplier=1L<<Math.min(30,failures-snapshot.threshold()+1);
                long cap=Math.max(snapshot.maxBackoff(),snapshot.intervalSeconds());
                long delay=snapshot.intervalSeconds()>cap/multiplier?cap:Math.min(snapshot.intervalSeconds()*multiplier,cap);
                backoffUntil=(claimedAt==null?now:claimedAt).plusSeconds(delay);
                if(!Long.valueOf(delay).equals(lastBackoffDelay))log.info("[COLLECT_DEVICE_BACKOFF] groupId={} deviceId={} failures={} code={} delaySec={}",snapshot.spec().groupId(),sourceId,failures,lastFailureCode,delay);
                lastBackoffDelay=delay;
            }
        }
        synchronized DeviceResultResponse response(String jobId,Snapshot snapshot,Integer id){
            return new DeviceResultResponse(jobId,snapshot.spec().taskId(),snapshot.spec().groupId(),snapshot.spec().protocol(),id,
                    targetCount,failedTargets,lastAttemptAt,lastOutcome,lastSuccessAt,lastFailureAt,lastFailureCode,lastFailureReason,
                    failures,inFlightSince!=null,inFlightSince,backoffUntil,skippedBackoff,skippedOverrun,snapshot.threshold(),snapshot.intervalSeconds(),snapshot.enabled());
        }
    }
}
