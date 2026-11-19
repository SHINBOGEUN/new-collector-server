package net.vivans.dcim.module.ingest.manager.infrastructure;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerMqttApiResponse;
import org.springframework.core.ParameterizedTypeReference;
/** 저장 직전의 현재 설정 확인. 허가 캐시는 두지 않으며 Manager 장애 시 저장하지 않는다. */
@Component @Slf4j
public class CollectionWriteGate {
    private final RestClient client;
    private final ManagerServiceProperties properties;
    private final java.util.concurrent.atomic.AtomicLong lastWarning=new java.util.concurrent.atomic.AtomicLong();
    public CollectionWriteGate(RestClient managerRestClient,ManagerServiceProperties properties){
        var factory=new org.springframework.http.client.JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build());
        factory.setReadTimeout(java.time.Duration.ofSeconds(2));
        this.client=managerRestClient.mutate().requestFactory(factory).build();this.properties=properties;
    }
    public record Request(int deviceId,String protocol,Integer sourceDeviceId,List<String> pointNames,String identityType,String externalId) {}
    public record Permission(boolean allowed,List<String> pointNames) {}
    public Set<String> allowed(int deviceId,String protocol,Integer source,List<String> names,String identityType,String externalId){
        if(!properties.isEnabled())return Set.of();
        try {
            var response=client.post().uri("/api/internal/collection/write-permission").body(new Request(deviceId,protocol,source,names,identityType,externalId)).retrieve()
                .body(new ParameterizedTypeReference<ManagerMqttApiResponse<Permission>>(){});
            if(response==null || !Integer.valueOf(200).equals(response.status()) || response.data()==null)throw new IllegalStateException("missing write permission");
            return response.data().allowed() && response.data().pointNames()!=null?new HashSet<>(response.data().pointNames()):Set.of();
        }catch(RuntimeException failure){
            long now=System.currentTimeMillis(),previous=lastWarning.get();
            if(now-previous>=30000 && lastWarning.compareAndSet(previous,now))log.warn("[COLLECTION_WRITE_DEFERRED] permission unavailable cause={} (new samples discarded; history kept)",failure.getClass().getSimpleName());
            return Set.of();
        }
    }
}
