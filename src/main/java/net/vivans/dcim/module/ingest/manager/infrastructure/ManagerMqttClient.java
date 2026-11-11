package net.vivans.dcim.module.ingest.manager.infrastructure;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class ManagerMqttClient {
    private final RestClient managerRestClient;

    public List<MqttIdentityResponse> identities() {
        var response = managerRestClient.get().uri("/api/manager/mqtt/identities/bulk").retrieve()
                .body(new ParameterizedTypeReference<ManagerMqttApiResponse<List<MqttIdentityResponse>>>() {});
        return requiredData(response);
    }

    public List<MqttPointResponse> points() {
        var response = managerRestClient.get().uri("/api/manager/mqtt/mappings/models/bulk").retrieve()
                .body(new ParameterizedTypeReference<ManagerMqttApiResponse<List<MqttPointResponse>>>() {});
        return requiredData(response);
    }

    public Optional<MqttIdentityResponse> resolve(String type, String externalId) {
        var response = managerRestClient.get().uri(builder -> builder
                .path("/api/manager/mqtt/identities/resolve")
                .queryParam("identityType", type).queryParam("externalId", "{externalId}")
                .build(externalId)).retrieve()
                .body(new ParameterizedTypeReference<ManagerMqttApiResponse<MqttIdentityResponse>>() {});
        if (response == null || !Integer.valueOf(200).equals(response.status())) {
            throw new IllegalStateException("Invalid manager MQTT resolve response");
        }
        return Optional.ofNullable(response.data());
    }

    private static <T> T requiredData(ManagerMqttApiResponse<T> response) {
        if (response == null || !Integer.valueOf(200).equals(response.status()) || response.data() == null) {
            throw new IllegalStateException("Invalid manager MQTT configuration response");
        }
        return response.data();
    }
}
