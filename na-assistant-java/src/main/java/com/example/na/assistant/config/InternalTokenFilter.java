package com.example.na.assistant.config;

import com.example.na.assistant.web.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 内部 token 校验。
 *
 * <p>与 Python 的 {@code require_token} 等价，并沿用支付服务的防御思路：
 * <b>拒绝优先</b>——只精确放行少数公开路径，其余一律要求 token。
 * 这样 {@code /%69nternal/...}、{@code //internal/...} 之类的编码变体无法绕过。
 *
 * <p>token 比较使用 {@link MessageDigest#isEqual}，避免字符串比较的时序侧信道。
 */
@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = new HashSet<>(
            Arrays.asList("/health", "/error"));

    private final byte[] expectedToken;

    private final ObjectMapper objectMapper;

    public InternalTokenFilter(AssistantProperties properties, ObjectMapper objectMapper) {
        String token = properties.getInternalToken();
        this.expectedToken = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader("X-Service-Token");
        // 未配置 token 时拒绝一切（与 Python 的 `not expected` 分支一致）
        if (expectedToken.length == 0 || provided == null
                || !MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            writeUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse("unauthorized")));
    }
}
