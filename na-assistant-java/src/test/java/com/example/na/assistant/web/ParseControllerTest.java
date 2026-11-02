package com.example.na.assistant.web;

import com.example.na.assistant.client.ChatCompletionClient;
import com.example.na.assistant.client.UpstreamStatusException;
import com.example.na.assistant.client.UpstreamTimeoutException;
import com.example.na.assistant.config.AssistantProperties;
import com.example.na.assistant.service.ParseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口行为测试：鉴权、健康检查、降级、校验失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.assistant.internal-token=test-token",
        "app.assistant.base-url=http://localhost:9999",
        "app.assistant.model=test-model",
        "app.assistant.api-key=test-key"
})
class ParseControllerTest {

    private static final String TOKEN = "test-token";

    private static final String PARSE_PATH = "/internal/v1/assistant/parse";

    private static final String VALID_BODY = "{\"requestId\":\"drf_test\",\"userId\":1,"
            + "\"text\":\"帮我拿两个机械键盘\",\"excludeProductIds\":[],"
            + "\"catalog\":[{\"id\":1,\"name\":\"机械键盘 K87\",\"description\":\"d\","
            + "\"category\":\"外设\",\"tags\":\"键盘\",\"price\":299.00,\"stock\":25}]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AssistantProperties properties;

    @MockBean
    private ChatCompletionClient chatCompletionClient;

    @BeforeEach
    void setUp() {
        properties.setInternalToken(TOKEN);
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void parseWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(PARSE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("unauthorized"));
    }

    @Test
    void parseWithWrongTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post(PARSE_PATH)
                        .header("X-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void encodedPathCannotBypassAuth() throws Exception {
        // /%69nternal/... 解码后是 /internal/...，必须仍要求 token
        mockMvc.perform(post("/%69nternal/v1/assistant/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void successfulParseReturnsOk() throws Exception {
        when(chatCompletionClient.complete(anyList())).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":"
                        + "\"{\\\"items\\\":[{\\\"productId\\\":1,\\\"quantity\\\":2}],"
                        + "\\\"unmatched\\\":[],\\\"questions\\\":[]}\"}}],"
                        + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20}}");

        mockMvc.perform(post(PARSE_PATH)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.items[0].productId").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.usage.promptTokens").value(100));
    }

    @Test
    void timeoutDegradesTo200WithReason() throws Exception {
        when(chatCompletionClient.complete(anyList()))
                .thenThrow(new UpstreamTimeoutException("timeout"));

        mockMvc.perform(post(PARSE_PATH)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                // 关键：降级是 200，不是 5xx
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.reason").value("TIMEOUT"));
    }

    @Test
    void upstreamErrorDegradesTo200() throws Exception {
        when(chatCompletionClient.complete(anyList()))
                .thenThrow(new UpstreamStatusException(500));

        mockMvc.perform(post(PARSE_PATH)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("UPSTREAM_ERROR"));
    }

    @Test
    void invalidModelOutputDegradesTo200() throws Exception {
        when(chatCompletionClient.complete(anyList())).thenReturn(
                "{\"choices\":[{\"message\":{\"content\":\"这不是 JSON\"}}]}");

        mockMvc.perform(post(PARSE_PATH)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("INVALID_OUTPUT"));
    }

    @Test
    void unknownFieldIsRejectedWith400() throws Exception {
        String body = "{\"requestId\":\"r1\",\"text\":\"x\",\"unexpected\":1}";

        mockMvc.perform(post(PARSE_PATH)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求结构不合契约"));
    }

    @Test
    void missingTextIsRejectedWith400() throws Exception {
        String body = "{\"requestId\":\"r1\"}";

        mockMvc.perform(post(PARSE_PATH)
                        .header("X-Service-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
