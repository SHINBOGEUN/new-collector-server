package net.vivans.dcim.module.job.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vivans.dcim.bootstrap.CollectorServerApplication;
import net.vivans.dcim.module.job.domain.CollectionGroupOidSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupSpec;
import net.vivans.dcim.module.job.domain.CollectionGroupTargetSpec;
import net.vivans.dcim.module.snmp.SnmpQueryClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CollectorServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SnmpQueryClient snmpQueryClient;

    @Test
    void healthDoesNotRequireApiKey() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    void jobsRequireApiKey() throws Exception {
        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerUpdateToggleDeleteJob() throws Exception {
        when(snmpQueryClient.get(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyList()))
                .thenReturn(Map.of("V", 220.1));

        CollectionGroupSpec spec = new CollectionGroupSpec(
                1,
                11,
                10,
                "snmp",
                "0 */1 * * * *",
                "public",
                2000,
                1,
                10,
                List.of(new CollectionGroupOidSpec("V", "1.3.6.1.4.1.6375.1.1.0", false)),
                List.of(new CollectionGroupTargetSpec(3, "192.168.14.114", 161, null)),
                List.of()
        );

        String created = mockMvc.perform(post("/api/jobs/register")
                        .header("X-Api-Key", "test-manager-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(spec)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.groupId").value(11))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String jobId = objectMapper.readTree(created).path("data").path("collectorJobId").asText();

        mockMvc.perform(post("/api/jobs/register")
                        .header("X-Api-Key", "test-manager-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(spec)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.collectorJobId").value(jobId));

        mockMvc.perform(patch("/api/jobs/" + jobId + "/toggle")
                        .header("X-Api-Key", "test-manager-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(delete("/api/jobs/" + jobId)
                        .header("X-Api-Key", "test-manager-key"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/jobs")
                        .header("X-Api-Key", "test-manager-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
