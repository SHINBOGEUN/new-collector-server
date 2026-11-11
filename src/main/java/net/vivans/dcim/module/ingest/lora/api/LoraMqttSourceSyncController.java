package net.vivans.dcim.module.ingest.lora.api;

import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.ingest.mqtt.infrastructure.LoraMqttSubscriber;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 공통 MQTT 소스 변경 알림. 이전 LoRa URL은 호환용 별칭으로 유지한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/internal/mqtt/sources", "/api/internal/lora/sources"})
public class LoraMqttSourceSyncController {
    private final LoraMqttSubscriber loraMqttSubscriber;

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        loraMqttSubscriber.requestRefresh();
        return ResponseEntity.noContent().build();
    }
}
