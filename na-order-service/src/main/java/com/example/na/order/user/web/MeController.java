package com.example.na.order.user.web;

import com.example.na.order.user.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@Tag(name = "用户")
public class MeController {

    @GetMapping("/me")
    @Operation(summary = "当前登录用户")
    public Map<String, Object> me(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", user.getUserId());
        result.put("username", user.getUsername());
        result.put("displayName", user.getDisplayName());
        return result;
    }
}
