package net.vivans.dcim.module.ingest.lora.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoraPayloadPathResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode parse(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    @Test
    void resolve_readsDotSeparatedNestedField() throws Exception {
        JsonNode root = parse("{\"object\":{\"TempC_SHT\":23.5,\"Hum_SHT\":55.1}}");

        assertThat(LoraPayloadPathResolver.resolve(root, "object.TempC_SHT").asDouble()).isEqualTo(23.5);
        assertThat(LoraPayloadPathResolver.resolve(root, "object.Hum_SHT").asDouble()).isEqualTo(55.1);
    }

    @Test
    void resolve_readsArrayIndexSegment() throws Exception {
        JsonNode root = parse("{\"rxInfo\":[{\"rssi\":-80,\"snr\":7.5},{\"rssi\":-95,\"snr\":3.0}]}");

        assertThat(LoraPayloadPathResolver.resolve(root, "rxInfo[0].rssi").asInt()).isEqualTo(-80);
        assertThat(LoraPayloadPathResolver.resolve(root, "rxInfo[1].snr").asDouble()).isEqualTo(3.0);
    }

    @Test
    void resolve_returnsNullWhenFieldMissing() throws Exception {
        JsonNode root = parse("{\"object\":{\"TempC_SHT\":23.5}}");

        assertThat(LoraPayloadPathResolver.resolve(root, "object.NotPresent")).isNull();
        assertThat(LoraPayloadPathResolver.resolve(root, "notPresent.at.all")).isNull();
    }

    @Test
    void resolve_returnsNullWhenArrayIndexOutOfBounds() throws Exception {
        JsonNode root = parse("{\"rxInfo\":[{\"rssi\":-80}]}");

        assertThat(LoraPayloadPathResolver.resolve(root, "rxInfo[5].rssi")).isNull();
    }

    @Test
    void resolve_returnsNullForNullOrBlankInputs() throws Exception {
        JsonNode root = parse("{\"a\":1}");

        assertThat(LoraPayloadPathResolver.resolve(null, "a")).isNull();
        assertThat(LoraPayloadPathResolver.resolve(root, null)).isNull();
        assertThat(LoraPayloadPathResolver.resolve(root, "")).isNull();
    }

    @Test
    void resolve_returnsNullForJsonNullValue() throws Exception {
        JsonNode root = parse("{\"object\":{\"TempC_SHT\":null}}");

        assertThat(LoraPayloadPathResolver.resolve(root, "object.TempC_SHT")).isNull();
    }
}
