package com.example.na.order.assistant.web;

import com.example.na.order.assistant.api.AssistantPort;
import com.example.na.order.assistant.domain.DraftResult;
import com.example.na.order.user.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
@Tag(name = "AI 助理")
public class AssistantController {

    private final AssistantPort assistantPort;

    @PostMapping("/drafts")
    @Operation(summary = "根据自然语言生成购物车草稿")
    public DraftResult createDraft(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
                                   @RequestBody AssistantDraftRequest request) {
        return assistantPort.createDraft(user.getUsername(), user.getUserId(), request.getText());
    }
}
