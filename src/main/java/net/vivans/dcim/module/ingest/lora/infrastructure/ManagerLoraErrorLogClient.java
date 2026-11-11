package net.vivans.dcim.module.ingest.lora.infrastructure;

import lombok.extern.slf4j.Slf4j;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraIngestErrorLogRequest;
import org.springframework.stereotype.Component;

/** Manager에 오류 적재 API가 없어 서비스 로그로 기록한다. raw payload는 출력하지 않는다. */
@Slf4j
@Component
public class ManagerLoraErrorLogClient {

    public void record(LoraIngestErrorLogRequest request) {
        log.warn("[LORA_INGEST_ERROR] deviceId={} reason={}", request.deviceId(), request.reason());
    }
}
