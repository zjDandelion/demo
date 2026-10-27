package com.example.na.order.assistant.web;

import com.example.na.order.catalog.domain.Product;
import com.example.na.order.catalog.repository.ProductRepository;
import com.example.na.order.user.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AssistantApiTest {

    private static final String SCENARIO_A = "帮我拿两个机械键盘，再加一张升降桌";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    private Long keyboardId;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        keyboardId = productRepository.save(new Product("机械键盘 K87", "87 键热插拔机械键盘，白色背光",
                new BigDecimal("299.00"), 25, true, "外设", "键盘,机械键盘,keyboard,输入设备")).getId();
        productRepository.save(new Product("无线鼠标 M330", "静音无线鼠标，续航 18 个月",
                new BigDecimal("129.00"), 40, true, "外设", "鼠标,鼠标器,无线,mouse,输入设备"));
        productRepository.save(new Product("人体工学椅", "可调腰托与扶手，网布透气",
                new BigDecimal("1099.00"), 6, true, "家具", "椅子,工学椅,座椅,chair"));
    }

    @Test
    void unauthenticatedRequestReturns401Json() throws Exception {
        mockMvc.perform(post("/api/assistant/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SCENARIO_A)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("未登录或会话已过期"));
    }

    @Test
    void blankTextReturns400() throws Exception {
        mockMvc.perform(post("/api/assistant/drafts")
                        .with(user(principal("blank-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请输入 1–300 字的商品描述"));
    }

    @Test
    void tooLongTextReturns400() throws Exception {
        char[] chars = new char[301];
        Arrays.fill(chars, '机');

        mockMvc.perform(post("/api/assistant/drafts")
                        .with(user(principal("long-user")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new String(chars))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scenarioAReturnsRuleDraftWithDisabledReason() throws Exception {
        mockMvc.perform(post("/api/assistant/drafts")
                        .with(user(principal("scenario-a")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(SCENARIO_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftId").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(keyboardId))
                .andExpect(jsonPath("$.items[0].name").value("机械键盘 K87"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].unitPrice").value(299.00))
                .andExpect(jsonPath("$.items[0].subtotal").value(598.00))
                .andExpect(jsonPath("$.items[0].stockShortage").value(false))
                .andExpect(jsonPath("$.items[0].source").value("RULE"))
                .andExpect(jsonPath("$.items[0].score").value(0.9))
                .andExpect(jsonPath("$.unmatched[0].text").value("升降桌"))
                .andExpect(jsonPath("$.questions").isEmpty())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.degradedReason").value("DISABLED"))
                .andExpect(jsonPath("$.confidence").value(0.9));
    }

    @Test
    void eleventhRequestInWindowReturns429() throws Exception {
        AuthenticatedUser principal = principal("burst-user");
        for (int attempt = 0; attempt < 10; attempt++) {
            mockMvc.perform(post("/api/assistant/drafts")
                            .with(user(principal))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("机械键盘")))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/api/assistant/drafts")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("机械键盘")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("请求过于频繁，请稍后再试"));
    }

    private static String body(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    private static AuthenticatedUser principal(String username) {
        return new AuthenticatedUser(1L, username, "not-used", username);
    }
}
