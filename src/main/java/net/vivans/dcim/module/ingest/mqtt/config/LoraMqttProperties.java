package net.vivans.dcim.module.ingest.mqtt.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * LoRa MQTT 수집 서브시스템 설정.
 * 실제 broker/topic 및 활성 여부는 Manager의 LoRa endpoint·lora_mqtt_source에서 받아온다.
 * 이 설정은 매핑 경로와 자격증명 키 해석만 담당한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ingest.mqtt.lora")
public class LoraMqttProperties {

    /** credentialKey별 실제 MQTT 자격증명. DB에는 이 key만 저장하고 password는 환경변수에만 둔다. */
    private Map<String, Credentials> credentials = new HashMap<>();

    /** ChirpStack uplink event에서 devEUI를 찾을 JSON 경로. 실제 payload로 미확인 — 확인 전까지 가정값. */
    private String devEuiPath = "deviceInfo.devEui";

    /** ChirpStack uplink event에서 deviceName을 찾을 JSON 경로. 레거시 sensor-data-service 코드로 확인됨. */
    private String deviceNamePath = "deviceInfo.deviceName";

    /** Manager 설정(endpoint/매핑) TTL 캐시 갱신 주기(ms) */
    private long configCacheRefreshMs = 300_000L;

    /** 오류 로그로 보낼 raw payload 최대 길이 (Manager DB 컬럼 한도인 4000보다 작거나 같게 유지) */
    private int errorRawPayloadMaxLength = 2000;

    public Credentials credentialsFor(String credentialKey) {
        if (credentialKey == null || credentialKey.isBlank()) {
            return new Credentials();
        }
        return credentials.getOrDefault(credentialKey, new Credentials("", ""));
    }

    @Getter
    @Setter
    public static class Credentials {
        private String username = "";
        private String password = "";

        public Credentials() {
        }

        public Credentials(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}
