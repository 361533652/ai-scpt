package com.gmscpt.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * 本地 API 的访问控制。
 *
 * <p>三道闸：
 * <ol>
 *   <li>{@code /api/**} 必须带正确 token（请求头 {@code X-GmScript-Token} 或查询参数 {@code token}）；</li>
 *   <li>写请求若带 {@code Origin}，其主机必须是回环地址（挡住任意网页对本地端口发起的 CSRF）；</li>
 *   <li>token 比对用常量时间比较，避免计时侧信道（虽然本机场景意义有限，成本为零）。</li>
 * </ol>
 */
public class ApiSecurityFilter extends OncePerRequestFilter {

    private final AccessToken token;
    private final GmScriptProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiSecurityFilter(AccessToken token, GmScriptProperties props) {
        this.token = token;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true; // 静态资源不设闸（里面本来就没有敏感信息）
        }
        return props.isAuthDisabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isOriginAllowed(request)) {
            reject(response, 403, "ORIGIN_REJECTED", "请求来源不是本机回环地址，已拒绝");
            return;
        }
        String presented = request.getHeader("X-GmScript-Token");
        if (presented == null || presented.isBlank()) {
            presented = request.getParameter("token");
        }
        if (!token.matches(presented)) {
            reject(response, 401, "UNAUTHORIZED", "缺少或错误的访问令牌，请用启动时打印的带 token 的地址打开页面");
            return;
        }
        chain.doFilter(request, response);
    }

    /** GET/HEAD 不校验来源（读操作也被 token 保护），写操作要求 Origin 是回环。 */
    private boolean isOriginAllowed(HttpServletRequest request) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            // 不是浏览器发起的（curl / 本机客户端），放行——它们拿不到别的站点的 cookie，也没有被 CSRF 的价值
            return true;
        }
        try {
            String host = URI.create(origin).getHost();
            return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(Map.of("code", code, "message", message)));
    }
}
