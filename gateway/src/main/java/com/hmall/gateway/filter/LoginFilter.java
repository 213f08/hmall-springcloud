package com.hmall.gateway.filter;

import cn.hutool.core.collection.CollUtil;
import com.hmall.gateway.config.AuthProperties;
import com.hmall.gateway.utils.JwtTool;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关登录校验：解析 JWT，校验通过后把用户 id 写进转发给微服务的请求头 user-info
 */
@Component
@RequiredArgsConstructor
public class LoginFilter implements GlobalFilter, Ordered {

    private final JwtTool jwtTool;
    private final AuthProperties authProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.获取请求
        ServerHttpRequest request = exchange.getRequest();
        // 2.白名单路径直接放行
        if (isExclude(request.getPath().toString())) {
            return chain.filter(exchange);
        }
        // 3.取出请求头中的 token
        String token = null;
        List<String> headers = request.getHeaders().get("authorization");
        if (CollUtil.isNotEmpty(headers)) {
            token = headers.get(0);
        }
        // 4.校验并解析 token
        Long userId;
        try {
            userId = jwtTool.parseToken(token);
        } catch (Exception e) {
            // token 无效或缺失，拦截
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        // 5.校验通过，把用户信息写入下游请求头，再放行
        String userInfo = userId.toString();
        ServerWebExchange newExchange = exchange.mutate()
                .request(builder -> builder.header("user-info", userInfo))
                .build();
        return chain.filter(newExchange);
    }

    private boolean isExclude(String path) {
        List<String> excludePaths = authProperties.getExcludePaths();
        if (CollUtil.isEmpty(excludePaths)) {
            return false;
        }
        for (String pattern : excludePaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        // 值越小优先级越高
        return 0;
    }
}
