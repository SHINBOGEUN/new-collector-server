package net.vivans.dcim.module.ingest.manager.infrastructure;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
public class AlarmMeasurementClient {
    private final RestClient client;
    private final boolean enabled;

    /** Manager 내부 API는 다른 Manager 조회와 같은 키(MANAGER_API_KEY)로 호출한다. */
    public AlarmMeasurementClient(ManagerServiceProperties properties) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().baseUrl(properties.getUrl()).requestFactory(factory)
                .defaultHeader("X-Api-Key", properties.getApiKey()).build();
        this.enabled = properties.isEnabled();
    }

    public void afterWrite(int deviceId, String protocol, Instant measuredAt, Map<String, Double> values) {
        if (!enabled || values.isEmpty()) return;
        try {
            client.post().uri("/api/internal/alarms/measurements")
                    .body(new Measurement(deviceId, protocol, measuredAt, values))
                    .retrieve().toBodilessEntity();
            log.debug("Alarm measurement delivered deviceId={} protocol={} pointCount={}",
                    deviceId, protocol, values.size());
        } catch (RuntimeException exception) {
            // Influx writes must remain successful even when Manager is temporarily unavailable.
            log.warn("Alarm measurement delivery failed deviceId={} protocol={}: {}",
                    deviceId, protocol, exception.getMessage());
        }
    }

    private record Measurement(int deviceId, String protocol, Instant measuredAt, Map<String, Double> values) {}
}
