package net.vivans.dcim.module.ingest.manager.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;

class ManagerMqttClientTest {
    @Test
    void usesCommonManagerUrlsAndReadsWrappersWithAdditionalMetadata() {
        var builder = RestClient.builder().baseUrl("http://manager").defaultHeader("X-Api-Key", "test-key");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new ManagerMqttClient(builder.build());
        server.expect(requestTo("http://manager/api/manager/mqtt/identities/bulk"))
                .andExpect(header("X-Api-Key", "test-key"))
                .andRespond(withSuccess("""
                    {"status":200,"timestamp":"2026-10-14T08:11:13Z","data":[
                    {"identityId":12,"identityType":"MAC","externalId":"aabbccddeeff",
                     "deviceId":40,"deviceModelId":8,"enabled":true}]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://manager/api/manager/mqtt/mappings/models/bulk"))
                .andRespond(withSuccess("""
                    {"status":200,"data":[{"id":1,"deviceModelId":8,"pointName":"gpu_temperature",
                    "payloadField":"object.gpu_temperature","componentScope":"COMPONENT",
                    "componentType":"GPU","componentPath":"components","componentIndexField":"index",
                    "componentUuidField":"uuid","categoryCodeId":85,"unitCodeId":58,"enabled":true}]}
                    """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://manager/api/manager/mqtt/identities/resolve?identityType=SERIAL&externalId=server%20%26%20one"))
                .andRespond(withSuccess("{\"status\":200,\"data\":null}", MediaType.APPLICATION_JSON));
        assertThat(client.identities().get(0).deviceId()).isEqualTo(40);
        assertThat(client.points().get(0).componentUuidField()).isEqualTo("uuid");
        assertThat(client.resolve("SERIAL", "server & one")).isEmpty();
        server.verify();
    }

    @Test
    void invalidConfigurationResponseIsNotTreatedAsEmptySnapshot() {
        var builder = RestClient.builder().baseUrl("http://manager");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new ManagerMqttClient(builder.build());
        server.expect(requestTo("http://manager/api/manager/mqtt/identities/bulk"))
                .andRespond(withSuccess("{\"status\":200,\"data\":null}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::identities).isInstanceOf(IllegalStateException.class);
        server.verify();
    }
}
