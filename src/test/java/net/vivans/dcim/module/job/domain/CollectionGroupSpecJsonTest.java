package net.vivans.dcim.module.job.domain;

import net.vivans.dcim.module.job.domain.modbus.ModbusPointResolver;

import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionTargetSpec;

import net.vivans.dcim.module.job.domain.modbus.ModbusCollectionGroupSpec;

import net.vivans.dcim.module.job.domain.modbus.ModbusByteOrder;

import net.vivans.dcim.module.job.domain.snmp.SnmpCollectionGroupSpec;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionGroupSpecJsonTest {
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    @Test
    void legacySnmpJsonUsesSnmpTypesAndRoundTripsWithoutModbusFields() throws Exception {
        String json = """
                {"taskId":1,"groupId":11,"modelId":10,"protocol":"snmp",
                 "cronExpression":"0 * * * * *","community":"public",
                 "timeoutMs":2000,"retries":1,"maxConcurrency":10,
                 "oids":[{"name":"V","template":"1.3.6.{instanceId}.0","requiresInstance":true,"scale":0.1}],
                 "targets":[{"deviceId":3,"host":"host","port":161,"instanceId":7}],"skipped":[]}
                """;
        CollectionGroupSpec spec = mapper.readValue(json, CollectionGroupSpec.class);
        assertThat(spec).isInstanceOf(SnmpCollectionGroupSpec.class);
        SnmpCollectionGroupSpec snmp = (SnmpCollectionGroupSpec) spec;
        assertThat(snmp.targets().get(0).instanceId()).isEqualTo(7);
        assertThat(snmp.oids().get(0).name()).isEqualTo("V");
        String encoded = mapper.writerFor(CollectionGroupSpec.class).writeValueAsString(spec);
        assertThat(mapper.readTree(encoded)).isEqualTo(mapper.readTree(json));
        assertThat(mapper.readValue(encoded, CollectionGroupSpec.class)).isEqualTo(spec);
    }

    @Test
    void rdcJsonUsesGroupPointsAndContainsNoSnmpFields() throws Exception {
        String json = """
                {"taskId":1,"groupId":12,"modelId":4,"protocol":"modbus",
                 "cronExpression":"0 * * * * *","timeoutMs":2000,"retries":1,"maxConcurrency":10,
                 "points":[{"name":"TEMP","registerType":"INPUT","address":256,
                            "dataType":"INT16","byteOrder":"BA","scale":0.1}],
                 "targets":[{"deviceId":21,"host":"host","port":502,"unitId":1}],"skipped":[]}
                """;
        ModbusCollectionGroupSpec spec = (ModbusCollectionGroupSpec) mapper.readValue(json, CollectionGroupSpec.class);
        assertThat(spec.targets().get(0).points()).isNull();
        assertThat(spec.targets().get(0).unitId()).isEqualTo(1);
        assertThat(ModbusPointResolver.resolve(spec, spec.targets().get(0))).isEqualTo(spec.points());
        assertThat(spec.points().get(0).byteOrder()).isEqualTo(ModbusByteOrder.BA);
        String encoded = mapper.writerFor(CollectionGroupSpec.class).writeValueAsString(spec);
        JsonNode tree = mapper.readTree(encoded);
        assertThat(tree.has("community")).isFalse();
        assertThat(tree.has("oids")).isFalse();
        assertThat(tree.path("targets").get(0).has("instanceId")).isFalse();
        assertThat(mapper.readValue(encoded, CollectionGroupSpec.class)).isEqualTo(spec);
    }

    @Test
    void accuraJsonPreservesPerTargetPointsAndRepeatedDestination() throws Exception {
        String json = """
                {"taskId":1,"groupId":13,"modelId":5,"protocol":"modbus",
                 "cronExpression":"0 * * * * *","timeoutMs":2000,"retries":1,"maxConcurrency":10,
                 "targets":[
                   {"deviceId":17,"host":"accura","port":502,"unitId":0,
                    "points":[{"name":"TOTAL_WT","registerType":"HOLDING","address":11265,
                               "dataType":"FLOAT32","byteOrder":"CDAB","scale":1000}]},
                   {"deviceId":17,"host":"accura","port":502,"unitId":3,
                    "points":[{"name":"FAN_WT","registerType":"HOLDING","address":11665,
                               "dataType":"FLOAT32","byteOrder":"CDAB","scale":1000}]}],"skipped":[]}
                """;
        ModbusCollectionGroupSpec spec = (ModbusCollectionGroupSpec) mapper.readValue(json, CollectionGroupSpec.class);
        assertThat(spec.points()).isNull();
        assertThat(spec.targets()).extracting(ModbusCollectionTargetSpec::deviceId).containsExactly(17, 17);
        assertThat(spec.targets()).extracting(ModbusCollectionTargetSpec::unitId).containsExactly(0, 3);
        assertThat(ModbusPointResolver.resolve(spec, spec.targets().get(1)).get(0).name()).isEqualTo("FAN_WT");
        assertThat(mapper.readValue(mapper.writeValueAsString(spec), CollectionGroupSpec.class)).isEqualTo(spec);
    }

    @Test
    void explicitNullAndEmptyTargetPointsRemainDifferent() throws Exception {
        String json = """
                {"protocol":"modbus","points":[{"name":"TEMP","dataType":"INT16"}],
                 "targets":[{"deviceId":1,"points":null},{"deviceId":2,"points":[]}]}
                """;
        ModbusCollectionGroupSpec spec = (ModbusCollectionGroupSpec) mapper.readValue(json, CollectionGroupSpec.class);
        assertThat(ModbusPointResolver.resolve(spec, spec.targets().get(0))).hasSize(1);
        assertThat(ModbusPointResolver.resolve(spec, spec.targets().get(1))).isEmpty();
        assertThat(spec.points().get(0).effectiveByteOrder()).isEqualTo(ModbusByteOrder.AB);
        assertThat(spec.points().get(0).effectiveScale()).isEqualTo(1.0);
    }

    @Test
    void unknownOrMissingProtocolIsNotSilentlyTreatedAsSnmp() {
        assertThatThrownBy(() -> mapper.readValue("{\"protocol\":\"mqtt\"}", CollectionGroupSpec.class))
                .isInstanceOf(InvalidTypeIdException.class);
        assertThatThrownBy(() -> mapper.readValue("{}", CollectionGroupSpec.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }
}
