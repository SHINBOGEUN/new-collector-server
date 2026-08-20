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
}
