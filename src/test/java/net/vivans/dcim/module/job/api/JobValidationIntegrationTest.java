package net.vivans.dcim.module.job.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.vivans.dcim.bootstrap.CollectorServerApplication;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CollectorServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobValidationIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @MockitoBean private SnmpQueryClient snmp;

    @Test
    void invalidRegistrationCreatesNoJobAndInvalidUpdatePreservesExistingJob() throws Exception {
        for (String protocol : new String[] {"snmp", "modbus"}) {
            int group = protocol.equals("snmp") ? 9801 : 9802;
            ObjectNode valid = (ObjectNode) mapper.readTree("""
                    {"taskId":9800,"groupId":9801,"modelId":1,"protocol":"snmp",
                     "cronExpression":"0 0 0 1 1 *","timeoutMs":2000,"retries":1,"maxConcurrency":2,
                     "targets":[{"deviceId":17,"host":"localhost","port":161,"instanceId":1}],
                     "oids":[{"name":"POWER","template":"1.3.6.1.{instanceId}.0","requiresInstance":true}]}
                    """);
            valid.put("groupId", group);
            if (protocol.equals("modbus")) {
                valid.put("protocol", "modbus");
                valid.remove("oids");
                ObjectNode target = (ObjectNode) valid.path("targets").get(0);
                target.remove("instanceId");
                target.put("port", 502);
                target.put("unitId", 1);
                valid.set("points", mapper.readTree("""
                        [{"name":"POWER","registerType":"INPUT","address":0,"dataType":"INT16","byteOrder":"AB"}]
                        """));
            }
            ObjectNode invalid = valid.deepCopy();
            ObjectNode invalidTarget = (ObjectNode) invalid.path("targets").get(0);
            if (protocol.equals("snmp")) {
                invalidTarget.remove("instanceId");
            } else {
                invalidTarget.put("unitId", 248);
            }

            mvc.perform(post("/api/jobs/register").header("X-Api-Key", "test-manager-key")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(invalid)))
                    .andExpect(status().isBadRequest());
            assertThat(findGroup(group)).isNull();

            String response = mvc.perform(post("/api/jobs/register").header("X-Api-Key", "test-manager-key")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(valid)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String jobId = mapper.readTree(response).path("data").path("collectorJobId").asText();
            try {
                var before = findGroup(group);
                invalid.put("cronExpression", "0 0 0 2 1 *");
                mvc.perform(put("/api/jobs/" + jobId).header("X-Api-Key", "test-manager-key")
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(invalid)))
                        .andExpect(status().isBadRequest());
                assertThat(findGroup(group)).isEqualTo(before);
            } finally {
                mvc.perform(delete("/api/jobs/" + jobId).header("X-Api-Key", "test-manager-key"))
                        .andExpect(status().isOk());
            }
        }
    }

    private com.fasterxml.jackson.databind.JsonNode findGroup(int group) throws Exception {
        String response = mvc.perform(get("/api/jobs").header("X-Api-Key", "test-manager-key"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for (var job : mapper.readTree(response).path("data")) {
            if (job.path("groupId").asInt() == group) {
                return job;
            }
        }
        return null;
    }
}
