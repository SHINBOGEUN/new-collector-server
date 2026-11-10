package net.vivans.dcim.module.ingest.mqtt.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.mqtt.domain.SensorMqttPayload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensorMqttPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesLegacySchedulePayload() throws Exception {
        SensorMqttPayload payload = objectMapper.readValue("""
                {
                  "datetime": "2026-08-20 10:51:00",
                  "data": {
                    "9": {
                      "V": 219,
                      "W": 519
                    }
                  },
                  "type": "schedule"
                }
                """, SensorMqttPayload.class);

        assertThat(payload.type()).isEqualTo("schedule");
        assertThat(payload.data()).containsKey("9");
        assertThat(payload.data().get("9")).containsEntry("V", 219);
        assertThat(payload.resolvedProtocol()).isEqualTo("snmp");
    }

    @Test
    void parsesExplicitModbusProtocol() throws Exception {
        SensorMqttPayload payload = objectMapper.readValue("""
                {"datetime":"2026-09-30 12:34:56","type":"schedule",
                 "protocol":"modbus","data":{"101":{"POWER":1250.5}}}
                """, SensorMqttPayload.class);

        assertThat(payload.resolvedProtocol()).isEqualTo("modbus");
        assertThat(payload.data().get("101")).containsEntry("POWER", 1250.5);
    }

    @Test
    void rejectsExplicitBlankOrUnknownProtocol() {
        assertThatThrownBy(() -> new SensorMqttPayload(null, Map.of(), "schedule", " ").resolvedProtocol())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SensorMqttPayload(null, Map.of(), "schedule", "other").resolvedProtocol())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
