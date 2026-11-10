package net.vivans.dcim.module.ingest.lora.infrastructure.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Manager가 Sensor Data에 내려주는 연결 단위 MQTT 수집 소스 설정.
 * 공통 경로에서 받으므로 LoRa(CHIRPSTACK_LORA)와 에이전트(AGENT)가 함께 온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoraMqttSourceResponse(
        Integer id,
        String name,
        String sourceType,
        String brokerUrl,
        String topic,
        String clientId,
        String credentialKey,
        boolean enabled,
        long configVersion
) {

    public static final String TYPE_CHIRPSTACK_LORA = "CHIRPSTACK_LORA";
    public static final String TYPE_AGENT = "AGENT";

    /** 에이전트 payload는 LoRa uplink와 구조가 달라 처리기를 나눈다. */
    public boolean isAgent() {
        return TYPE_AGENT.equals(sourceType);
    }
}
