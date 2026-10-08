package com.hmall.cart.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {

    @Bean
    public ItemClientFallbackFactory itemClientFallback() {
        return new ItemClientFallbackFactory();
    }
}
