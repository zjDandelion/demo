package com.example.na.payment.config;

import com.example.na.payment.contract.InternalHeaders;
import org.springframework.beans.factory.annotation.Value;
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

@Component
public class InternalTokenFilter extends OncePerRequestFilter {

    /**
     * getRequestURI() 返回未解码的原始路径，而容器路由用的是解码、归一化后的路径，
     * 因此按前缀判断"是否内部接口"会被 /%69nternal/... 这类变体绕过。这里反过来只放行
     * 显式公开的少数路径，其余一律要求服务 token。
     */
    private static final Set<String> PUBLIC_PATHS = new HashSet<>(
            Arrays.asList("/actuator/health", "/error"));

    private final byte[] expectedToken;

    public InternalTokenFilter(@Value("${app.internal.token:}") String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String providedToken = request.getHeader(InternalHeaders.SERVICE_TOKEN);
        if (expectedToken.length == 0 || providedToken == null
                || !MessageDigest.isEqual(expectedToken, providedToken.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"UNAUTHORIZED_INTERNAL\",\"message\":\"内部服务认证失败\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
