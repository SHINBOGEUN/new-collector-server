package net.vivans.dcim.module.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MqttPayloadFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void payloadUsesLegacyEnvelopeWithDeviceIdKey() throws Exception {
        var payload = PahoMqttPublisher.buildPayload(
                9,
                Map.of("V", 219, "W", 519),
                LocalDateTime.of(2026, 8, 20, 10, 51, 0)
        );

        String json = objectMapper.writeValueAsString(payload);

        assertThat(payload.get("type")).isEqualTo("schedule");
        assertThat(payload.get("datetime")).isEqualTo("2026-08-20 10:51:00");
        assertThat(json).contains("\"9\"");
        assertThat(json).doesNotContain("device:9");
        assertThat(json).doesNotContain("taskId");
        assertThat(json).doesNotContain("groupId");
    }

    @Test
    void livePayloadIsOnePointPerMessage() throws Exception {
        var payload = PahoMqttPublisher.buildLivePayload(
                9,
                "PDU-1P-114",
                "W",
                "W",
                519,
                LocalDateTime.of(2026, 8, 21, 14, 40, 0)
        );

        assertThat(payload.get("type")).isEqualTo("realtime");
        assertThat(payload.get("deviceId")).isEqualTo(9);
        assertThat(payload.get("pointName")).isEqualTo("W");
        assertThat(payload.get("unit")).isEqualTo("W");
        assertThat(payload.get("value")).isEqualTo(519);
        assertThat(payload.get("displayName")).isEqualTo("PDU-1P-114");
        assertThat(payload.get("datetime")).isEqualTo("2026-08-21 14:40:00");
        assertThat(payload).doesNotContainKey("data");
    }
}
