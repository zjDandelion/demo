package com.example.na.order.assistant.web;

import com.example.na.order.assistant.client.AssistantClient;
import com.example.na.order.assistant.client.AssistantParseRequest;
import com.example.na.order.assistant.client.AssistantParseResponse;
import com.example.na.order.assistant.client.AssistantParsedItem;
import com.example.na.order.assistant.client.ParseOutcome;
import com.example.na.order.assistant.domain.DegradedReason;
import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import com.example.na.order.user.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.assistant.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantEnabledApiTest {

    private static final String SCENARIO_A = "帮我拿两个机械键盘，再加一张升降桌";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @MockBean
    private AssistantClient assistantClient;

    private Long keyboardId;
    private Long chairId;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        keyboardId = productRepository.save(new Product("机械键盘 K87", "87 键热插拔机械键盘，白色背光",
                new BigDecimal("299.00"), 25, true, "外设", "键盘,机械键盘,keyboard,输入设备")).getId();
        chairId = productRepository.save(new Product("人体工学椅", "可调腰托与扶手，网布透气",
                new BigDecimal("1099.00"), 6, true, "家具", "椅子,工学椅,座椅,chair")).getId();
    }

    @Test
    void modelItemIsMergedIntoDraft() throws Exception {
        when(assistantClient.parse(any(AssistantParseRequest.class))).thenReturn(ParseOutcome.success(
                new AssistantParseResponse("OK", null,
                        Collections.singletonList(new AssistantParsedItem(chairId, 1)),
                        Collections.emptyList(), Collections.emptyList(), null)));

        mockMvc.perform(post("/api/assistant/drafts")
                        .with(user(principal("merge-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SCENARIO_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(keyboardId))
                .andExpect(jsonPath("$.items[0].source").value("RULE"))
                .andExpect(jsonPath("$.items[1].productId").value(chairId))
                .andExpect(jsonPath("$.items[1].source").value("MODEL"))
                .andExpect(jsonPath("$.items[1].score").value(0.6))
                .andExpect(jsonPath("$.unmatched").isEmpty())
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.degradedReason").doesNotExist())
                .andExpect(jsonPath("$.confidence").value(0.6));

        verify(assistantClient).parse(any(AssistantParseRequest.class));
    }

    @Test
    void degradedUpstreamStillReturns200WithReason() throws Exception {
        when(assistantClient.parse(any(AssistantParseRequest.class)))
                .thenReturn(ParseOutcome.failed(DegradedReason.TIMEOUT));

        mockMvc.perform(post("/api/assistant/drafts")
                        .with(user(principal("degraded-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SCENARIO_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.unmatched[0].text").value("升降桌"))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradedReason").value("TIMEOUT"));
    }

    private static String body(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    private static AuthenticatedUser principal(String username) {
        return new AuthenticatedUser(1L, username, "not-used", username);
    }
}
