package com.example.na.assistant.web;

import com.example.na.assistant.domain.DegradedReason;
import com.example.na.assistant.service.ParseService;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理。
 *
 * <p>两条与 Python 对齐的关键约定：
 * <ul>
 *   <li>请求结构问题 → <b>400</b> + {@code {"message": "请求结构不合契约"}}；</li>
 *   <li>其他未捕获异常 → <b>200</b> + {@code DEGRADED/UPSTREAM_ERROR}
 *       （Python 的兜底 handler 就是这么做的，不能改成 500，
 *       否则订单侧会把它当成服务不可用而不是降级）。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ParseService parseService;

    public GlobalExceptionHandler(ParseService parseService) {
        this.parseService = parseService;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, BindException.class,
            MethodArgumentNotValidException.class, JsonMappingException.class})
    public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception) {
        log.error("assistant.parse invalid request: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("请求结构不合契约"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ParseResponse> handleUnexpected(Exception exception) {
        log.error("assistant.parse unexpected error: {}", exception.getClass().getSimpleName(), exception);
        return ResponseEntity.ok(parseService.degraded(DegradedReason.UPSTREAM_ERROR));
    }
}
