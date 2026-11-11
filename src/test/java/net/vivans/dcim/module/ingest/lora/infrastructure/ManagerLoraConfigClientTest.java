package net.vivans.dcim.module.ingest.lora.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerMqttClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerServiceProperties;
import net.vivans.dcim.module.ingest.lora.domain.LoraIdType;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;

class ManagerLoraConfigClientTest {
    @Test
    void adaptsCommonApiWithoutExposingGpuIdentitiesOrComponentsToLoraCache() {
        var builder = RestClient.builder().baseUrl("http://manager");
        var server = MockRestServiceServer.bindTo(builder).build();
        var rest = builder.build();
        var client = new ManagerLoraConfigClient(rest, new ManagerServiceProperties(), new ManagerMqttClient(rest), new ObjectMapper());
        server.expect(requestTo("http://manager/api/manager/mqtt/identities/bulk"))
                .andRespond(withSuccess("""
                    {"status":200,"data":[
                     {"identityId":1,"identityType":"DEV_EUI","externalId":"a8404124875d9d36",
                      "normalizedExternalId":"A8404124875D9D36","deviceId":41,"deviceModelId":9,"enabled":true},
                     {"identityId":2,"identityType":"MAC","externalId":"aabbccddeeff",
                      "deviceId":40,"deviceModelId":8,"enabled":true}]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://manager/api/manager/mqtt/mappings/models/bulk"))
                .andRespond(withSuccess("""
                    {"status":200,"data":[
                     {"id":1,"deviceModelId":9,"payloadField":"object.TempC_SHT","pointName":"TEMPERATURE",
                      "componentScope":"DEVICE","dataPointTypeCode":"TEMPERATURE","unit":"°C","scale":0.1,"enabled":true},
                     {"id":2,"deviceModelId":8,"payloadField":"object.gpu_temperature","pointName":"GPU_TEMP",
                      "componentScope":"COMPONENT","componentType":"GPU","enabled":true}]}
                    """, MediaType.APPLICATION_JSON));
        var endpoints = client.findAllEnabledEndpoints();
        assertThat(endpoints).hasSize(1);
        assertThat(endpoints.get(0).id()).isEqualTo(1);
        assertThat(endpoints.get(0).idType()).isEqualTo(LoraIdType.DEV_EUI);
        var points = client.findAllEnabledModelMappings();
        assertThat(points).hasSize(1);
        assertThat(points.get(0).payloadField()).isEqualTo("object.TempC_SHT");
        assertThat(points.get(0).pointName()).isEqualTo("TEMPERATURE");
        assertThat(points.get(0).scale()).isEqualTo(0.1);
        assertThat(points.get(0).unit()).isEqualTo("°C");
        // 모의 서버에 추가 기대 요청이 없으므로 HTTP 호출이 발생하면 이 테스트가 실패한다.
        assertThat(client.findAllEnabledOverrides()).isEmpty();
        server.verify();
    }

    @Test
    void resolveUsesNewIdentityTypeParameterButKeepsLegacyDeviceResponse() {
        var builder = RestClient.builder().baseUrl("http://manager");
        var server = MockRestServiceServer.bindTo(builder).build();
        var rest = builder.build();
        var client = new ManagerLoraConfigClient(rest, new ManagerServiceProperties(), new ManagerMqttClient(rest), new ObjectMapper());
        server.expect(requestTo("http://manager/api/manager/mqtt/identities/resolve?identityType=DEV_EUI&externalId=a8404124875d9d36"))
                .andRespond(withSuccess("""
                    {"status":200,"data":{"identityId":1,"identityType":"DEV_EUI",
                    "deviceId":41,"deviceName":"DS_TEMP_7","deviceModelId":9,"deviceModelName":"LHT65","enabled":true}}
                    """, MediaType.APPLICATION_JSON));
        var response = client.resolve(LoraIdType.DEV_EUI, "a8404124875d9d36").orElseThrow();
        assertThat(response.deviceId()).isEqualTo(41);
        assertThat(response.deviceName()).isEqualTo("DS_TEMP_7");
        server.verify();
    }
}
