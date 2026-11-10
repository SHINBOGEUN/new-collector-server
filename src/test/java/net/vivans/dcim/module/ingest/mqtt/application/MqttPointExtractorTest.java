package net.vivans.dcim.module.ingest.mqtt.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.manager.infrastructure.dto.MqttPointResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MqttPointExtractorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MqttPointExtractor extractor = new MqttPointExtractor(mapper);

    static MqttPointResponse point(String name, String path, boolean component, Double scale, String valueMap) {
        return new MqttPointResponse(1, 8, name, path, component ? "COMPONENT" : "DEVICE",
                component ? "GPU" : null, component ? "components" : "",
                component ? "index" : null, component ? "uuid" : null, scale, valueMap, true);
    }

    @Test
    void readsServerAndAnyNumberOfGpuCards() throws Exception {
        StringBuilder json = new StringBuilder("{\"object\":{\"load_avg_1min\":0.01},\"components\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) json.append(',');
            json.append("{\"type\":\"GPU\",\"index\":").append(i)
                    .append(",\"uuid\":\"GPU-").append(i).append("\",\"object\":{\"gpu_temperature\":59}}");
        }
        var results = extractor.extract(mapper.readTree(json.append("]}").toString()), List.of(
                point("load", "object.load_avg_1min", false, null, null),
                point("temp", "object.gpu_temperature", true, 1D, null)));
        assertThat(results).hasSize(11);
        assertThat(results.get(0).componentIndex()).isNull();
        assertThat(results.get(1).componentIndex()).isEqualTo("0");
        assertThat(results.get(10).componentIndex()).isEqualTo("9");
        assertThat(results.get(10).componentUuid()).isEqualTo("GPU-9");
    }

    @Test
    void skipsMissingNullNaNUnknownFieldsAndKeepsZero() throws Exception {
        var root = mapper.readTree("""
                {"object":{"null":null,"na":"N/A","nan":"NaN","inf":"Infinity","zero":0,"unknown":123}}
                """);
        var definitions = List.of("null", "na", "nan", "inf", "zero", "missing").stream()
                .map(name -> point(name, "object." + name, false, 1D, null)).toList();
        var results = extractor.extract(root, definitions);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).pointName()).isEqualTo("zero");
        assertThat(results.get(0).value()).isZero();
    }

    @Test
    void supportsLegacyLoraPathsScalingAndStateMapping() throws Exception {
        var root = mapper.readTree("{\"object\":{\"TempC_SHT\":23.89,\"state\":\"High\"}}");
        var results = extractor.extract(root, List.of(
                point("TEMP", "object.TempC_SHT", false, 2D, null),
                point("STATE", "object.state", false, null, "{\"High\":1,\"Low\":0}")));
        assertThat(results).extracting(r -> r.value()).containsExactly(47.78, 1D);
    }

    @Test
    void ignoresDuplicateSlotsMissingIdentityAndOtherComponentTypes() throws Exception {
        var root = mapper.readTree("""
                {"components":[
                 {"type":"GPU","index":0,"uuid":"A","object":{"temp":1}},
                 {"type":"GPU","index":0,"uuid":"B","object":{"temp":2}},
                 {"type":"GPU","uuid":"C","object":{"temp":3}},
                 {"type":"GPU","index":1,"object":{"temp":4}},
                 {"type":"CPU","index":2,"uuid":"D","object":{"temp":5}},
                 {"type":"GPU","index":4,"uuid":"E","object":{"temp":6}}]}
                """);
        var results = extractor.extract(root, List.of(point("temp", "object.temp", true, null, null)));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).componentIndex()).isEqualTo("4");
    }

    @Test
    void changedUuidRetainsLogicalSlotAndPathsSupportArrayIndices() throws Exception {
        var definition = point("temp", "object.temp", true, null, null);
        var before = extractor.extract(mapper.readTree("{\"components\":[{\"index\":0,\"uuid\":\"OLD\",\"object\":{\"temp\":1}}]}"), List.of(definition));
        var after = extractor.extract(mapper.readTree("{\"components\":[{\"index\":0,\"uuid\":\"NEW\",\"object\":{\"temp\":2}}]}"), List.of(definition));
        assertThat(before.get(0).componentIndex()).isEqualTo(after.get(0).componentIndex());
        assertThat(before.get(0).componentUuid()).isNotEqualTo(after.get(0).componentUuid());
        assertThat(MqttPointExtractor.readPath(mapper.readTree("{\"a\":[{\"b\":5}]}"), "$.a[0].b").asInt()).isEqualTo(5);
    }
}
