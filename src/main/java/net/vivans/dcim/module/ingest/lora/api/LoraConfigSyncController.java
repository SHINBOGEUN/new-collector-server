package net.vivans.dcim.module.ingest.lora.api;

import lombok.RequiredArgsConstructor;
import net.vivans.dcim.module.ingest.lora.application.LoraConfigCache;
import net.vivans.dcim.module.ingest.mqtt.infrastructure.LoraMqttSubscriber;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Manager의 endpoint/point mapping CRUD 후 LoRa 설정 캐시를 즉시 갱신한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/lora/config")
public class LoraConfigSyncController {
    private final LoraConfigCache loraConfigCache;
    private final LoraMqttSubscriber loraMqttSubscriber;
    private final net.vivans.dcim.module.ingest.mqtt.application.MqttConfigurationService mqttConfigurationService;

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        loraConfigCache.refresh();
        loraMqttSubscriber.requestRefresh();
        mqttConfigurationService.refresh();
        return ResponseEntity.noContent().build();
    }
}
