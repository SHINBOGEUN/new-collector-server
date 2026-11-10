package net.vivans.dcim.module.ingest.manager.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerDeviceApiResponse;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.ManagerDeviceResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ManagerDeviceClient {

    private final RestClient managerRestClient;
    private final ManagerServiceProperties properties;

    public Optional<ManagerDeviceResponse> findDevice(int deviceId) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            ManagerDeviceApiResponse response = managerRestClient.get()
                    .uri("/api/manager/devices/{id}", deviceId)
                    .retrieve()
                    .body(ManagerDeviceApiResponse.class);
            if (response == null || response.data() == null) {
                return Optional.empty();
            }
            return Optional.of(response.data());
        } catch (Exception exception) {
            log.warn("manager device lookup failed deviceId={}: {}", deviceId, exception.getMessage());
            return Optional.empty();
        }
    }
}
