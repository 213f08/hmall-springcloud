package com.hmall.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "hm.auth")
public class AuthProperties {

    /**
     * 白名单路径，命中就不做登录校验，Ant 风格，例如 /items/**
     */
    private List<String> excludePaths;
}
