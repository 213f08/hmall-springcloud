package com.hmall.common.config;

import com.hmall.common.feign.UserContextFeignInterceptor;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link UserContextFeignInterceptor} 注册成 Bean，Feign 会自动应用到所有远程调用。
 * 只在 classpath 上有 Feign 时生效，网关不会加载它。
 */
@Configuration
@ConditionalOnClass(RequestInterceptor.class)
public class DefaultFeignConfig {

    @Bean
    public UserContextFeignInterceptor userContextFeignInterceptor() {
        return new UserContextFeignInterceptor();
    }
}
