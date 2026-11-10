package net.vivans.dcim.module.ingest.mqtt.api;

import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.ingest.mqtt.application.MqttConfigurationService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

/** GPU·에이전트용 공통 MQTT 설정 갱신. LoRa 갱신은 기존 컨트롤러에서 처리한다. */
@RestController
@RequiredArgsConstructor
public class MqttConfigController {
    private final MqttConfigurationService configuration;

    @PostMapping("/api/internal/mqtt/config/refresh")
    public ResponseEntity<Void> refresh() {
        configuration.refresh();
        return ResponseEntity.noContent().build();
    }
}
