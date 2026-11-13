package com.cb.agent.api.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 所有 {@code /api/} 下的请求先过这里，做两件事。
 *
 * <p><b>一、核对令牌。</b>配置了 {@code agent.api.token} 之后，请求要带着
 * {@code Authorization: Bearer <令牌>}，不带或者不对的回 401。没配置时不检查。
 * 这是 Skill 中心（按调用方授权，方案第 9.8 节）做出来之前的办法：只有"能调"和"不能调"两种，分不出是谁。
 * 服务默认只在本机监听；要让别的机器调用时，一定要配上令牌，见 application.yml 里 {@code server.address} 的说明。
 *
 * <p><b>二、限制请求的大小。</b>请求体会整个读进内存，所以在读之前就按 {@code Content-Length} 拦下太大的（413）。
 * 没有 {@code Content-Length} 的（分块发送）没法事先知道大小，直接拒绝（411），让调用方换成带长度的发法。
 *
 * <p>健康检查和指标（{@code /actuator/}）不经过这里：里面没有文档的内容。
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiGuardFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/";

    private final byte[] token;
    private final long maxBodyBytes;
    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * @param token           令牌；留空表示不检查
     * @param maxRequestBytes 一次请求里的文件加起来最多多少字节（和 {@code UploadLimits} 用的是同一项配置）
     */
    public ApiGuardFilter(@Value("${agent.api.token:}") String token,
                          @Value("${agent.api.max-request-bytes:33554432}") long maxRequestBytes) {
        this.token = token == null || token.isBlank() ? null : token.strip().getBytes(StandardCharsets.UTF_8);
        // 文件放在 JSON 里时是 base64，比原来大三分之一；再留一点给文件之外的东西
        this.maxBodyBytes = maxRequestBytes / 3 * 4 + 1024 * 1024;
    }

    /** 有没有配置令牌。 */
    public boolean tokenRequired() {
        return token != null;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (token != null && !authorized(request)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            reject(response, 401, "unauthorized", "要带上令牌：Authorization: Bearer <令牌>。令牌是服务端配置的 agent.api.token");
            return;
        }
        String method = request.getMethod();
        if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
            long length = request.getContentLengthLong();
            if (length < 0) {
                if (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
                    reject(response, 411, "length_required", "请求要带 Content-Length，不接受分块发送（Transfer-Encoding: chunked）");
                    return;
                }
                // 既没有长度也不是分块：没有请求体（比如取消任务的那个 POST），放行
            } else if (length > maxBodyBytes) {
                reject(response, 413, "too_large", "请求有 " + length + " 字节，超过了上限 " + maxBodyBytes
                        + " 字节（由配置 agent.api.max-request-bytes 算出）。分几次提交");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean authorized(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        // 用耗时不随内容变化的比较，不让人靠响应的快慢一位一位地试出令牌
        return MessageDigest.isEqual(header.substring(7).strip().getBytes(StandardCharsets.UTF_8), token);
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(json.writeValueAsBytes(ApiExceptionHandler.body(code, message)));
    }
}
