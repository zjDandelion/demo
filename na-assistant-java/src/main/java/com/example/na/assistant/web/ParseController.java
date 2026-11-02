package com.example.na.assistant.web;

import com.example.na.assistant.config.AssistantProperties;
import com.example.na.assistant.service.ParseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 解析接口。
 *
 * <p>路径、请求/响应结构与 Python 服务完全一致，订单侧无需任何改动即可切换。
 */
@RestController
@RequestMapping("/internal/v1/assistant")
public class ParseController {

    private static final Logger log = LoggerFactory.getLogger(ParseController.class);

    private final ParseService parseService;

    private final AssistantProperties properties;

    public ParseController(ParseService parseService, AssistantProperties properties) {
        this.parseService = parseService;
        this.properties = properties;
    }

    @PostMapping("/parse")
    public ResponseEntity<?> parse(@Valid @RequestBody ParseRequest request) {
        // 与 Python 的 check_limits 对齐：schema 之外还有业务上限
        String limitError = checkLimits(request);
        if (limitError != null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(limitError));
        }

        // 日志只记录长度与哈希，避免把用户输入写进日志（与 Python 的隐私处理一致）
        log.info("assistant.parse received requestId={} userId={} textLength={} textHash={} catalog={}",
                request.getRequestId(), request.getUserId(),
                request.getText() == null ? 0 : request.getText().length(),
                digest(request.getText()),
                request.getCatalog() == null ? 0 : request.getCatalog().size());

        return ResponseEntity.ok(parseService.parse(request));
    }

    /**
     * 业务上限检查，返回错误信息或 null。
     */
    private String checkLimits(ParseRequest request) {
        if (request.getCatalog() != null && request.getCatalog().size() > properties.getMaxCatalog()) {
            return "目录超过上限";
        }
        if (request.getExcludeProductIds() != null
                && request.getExcludeProductIds().size() > properties.getMaxExclude()) {
            return "排除商品超过上限";
        }
        if (request.getText() != null && request.getText().length() > properties.getMaxTextLength()) {
            return "文本超过上限";
        }
        return null;
    }

    private static String digest(String text) {
        if (text == null) {
            return "";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 4; index++) {
                builder.append(String.format("%02x", hash[index]));
            }
            return builder.toString();
        } catch (Exception exception) {
            return "";
        }
    }
}
