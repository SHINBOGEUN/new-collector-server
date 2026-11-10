package net.vivans.dcim.module.ingest.lora.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.module.ingest.lora.infrastructure.dto.LoraMqttSourceStatusReportRequest;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerConfigUnavailableException;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerMqttClient;
import net.vivans.dcim.module.ingest.manager.infrastructure.ManagerServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 소스 목록·상태 보고가 LoRa 전용이 아닌 공통 MQTT 경로를 쓰는지 검증한다. */
class ManagerMqttSourceClientTest {

    @Test
    void sourceListUsesCommonPathAndIncludesAgentSources() {
        var builder = RestClient.builder().baseUrl("http://manager");
        var server = MockRestServiceServer.bindTo(builder).build();
        var rest = builder.build();
        var client = new ManagerLoraConfigClient(rest, new ManagerServiceProperties(),
                new ManagerMqttClient(rest), new ObjectMapper());
        server.expect(requestTo("http://manager/api/manager/mqtt/sources/bulk"))
                .andRespond(withSuccess("""
                    {"status":200,"data":[
                     {"id":1,"name":"서버실 게이트웨이","sourceType":"CHIRPSTACK_LORA",
                      "brokerUrl":"tcp://192.168.14.16:1883","topic":"application/#",
                      "clientId":null,"credentialKey":null,"enabled":true,"configVersion":3,
                      "createdDt":"2026-10-01T00:00:00Z","updatedDt":"2026-10-01T00:00:00Z","status":null},
                     {"id":2,"name":"GPU 에이전트","sourceType":"AGENT",
                      "brokerUrl":"tcp://10.0.0.5:1883","topic":"dcim/agent/#",
                      "clientId":null,"credentialKey":null,"enabled":true,"configVersion":1,
                      "createdDt":"2026-10-01T00:00:00Z","updatedDt":"2026-10-01T00:00:00Z","status":null}]}
                    """, MediaType.APPLICATION_JSON));

        var sources = client.findAllSources();

        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).isAgent()).isFalse();
        assertThat(sources.get(1).isAgent()).isTrue();
        assertThat(sources.get(1).topic()).isEqualTo("dcim/agent/#");
        server.verify();
    }

    @Test
    void statusReportUsesCommonPath() {
        var builder = RestClient.builder().baseUrl("http://manager");
        var server = MockRestServiceServer.bindTo(builder).build();
        var rest = builder.build();
        var client = new ManagerLoraConfigClient(rest, new ManagerServiceProperties(),
                new ManagerMqttClient(rest), new ObjectMapper());
        server.expect(requestTo("http://manager/api/manager/mqtt/sources/2/status"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("{\"status\":200,\"data\":{}}", MediaType.APPLICATION_JSON));

        client.reportSourceStatus(2, new LoraMqttSourceStatusReportRequest(
                "CONNECTED", Instant.now(), Instant.now(), 1, 0, 0, null, Instant.now()));

        server.verify();
    }

    @Test
    void sourceListFailureIsReportedInsteadOfEmptyList() {
        var builder = RestClient.builder().baseUrl("http://manager");
        var server = MockRestServiceServer.bindTo(builder).build();
        var rest = builder.build();
        var client = new ManagerLoraConfigClient(rest, new ManagerServiceProperties(),
                new ManagerMqttClient(rest), new ObjectMapper());
        server.expect(requestTo("http://manager/api/manager/mqtt/sources/bulk"))
                .andRespond(withServerError());

        assertThatThrownBy(client::findAllSources)
                .isInstanceOf(ManagerConfigUnavailableException.class);
        server.verify();
    }
}
