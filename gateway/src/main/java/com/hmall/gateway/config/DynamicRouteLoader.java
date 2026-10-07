package com.hmall.gateway.config;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 从 Nacos 读取路由配置，并监听变更，实现不重启网关就能增删路由
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicRouteLoader implements ApplicationEventPublisherAware {

    // 路由配置的 dataId，json 格式，见 nacos-config/gateway.json
    private static final String DATA_ID = "gateway.json";
    private static final String GROUP = "DEFAULT_GROUP";
    private static final long TIMEOUT = 5000;
    private static final TypeReference<List<RouteDefinition>> ROUTES = new TypeReference<List<RouteDefinition>>() {
    };

    private final RouteDefinitionWriter routeDefinitionWriter;
    private final NacosConfigManager nacosConfigManager;
    private final ObjectMapper objectMapper;

    private ApplicationEventPublisher publisher;
    // 上一次写入的路由id，用于删除 Nacos 中已经不存在的路由
    private final Set<String> currentRouteIds = new HashSet<>();

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostConstruct
    public void initRouteConfigListener() throws NacosException {
        // 1.注册监听器并首次拉取配置
        String configInfo = nacosConfigManager.getConfigService()
                .getConfigAndSignListener(DATA_ID, GROUP, TIMEOUT, new Listener() {
                    @Override
                    public Executor getExecutor() {
                        return null;
                    }

                    @Override
                    public void receiveConfigInfo(String configInfo) {
                        // 监听到配置变更，更新一次路由表
                        log.info("收到路由配置变更，更新路由表");
                        updateRoutes(configInfo);
                    }
                });
        // 2.首次启动时，更新一次配置
        updateRoutes(configInfo);
    }

    private void updateRoutes(String configInfo) {
        if (configInfo == null || configInfo.trim().isEmpty()) {
            log.warn("Nacos 中 {} 为空，跳过路由更新", DATA_ID);
            return;
        }
        List<RouteDefinition> definitions;
        try {
            definitions = objectMapper.readValue(configInfo, ROUTES);
        } catch (Exception e) {
            log.error("路由配置解析失败，保持原路由表不变", e);
            return;
        }
        // 1.删除本次配置中已经不存在的路由
        Set<String> newIds = new HashSet<>();
        definitions.forEach(d -> newIds.add(d.getId()));
        for (String id : currentRouteIds) {
            if (newIds.contains(id)) {
                continue;
            }
            routeDefinitionWriter.delete(Mono.just(id))
                    .onErrorResume(e -> Mono.empty())
                    .subscribe();
            log.info("删除路由：{}", id);
        }
        // 2.保存（id 相同会覆盖）最新的路由
        for (RouteDefinition definition : definitions) {
            routeDefinitionWriter.save(Mono.just(definition)).subscribe();
        }
        currentRouteIds.clear();
        currentRouteIds.addAll(newIds);
        // 3.发布刷新事件，让网关重建路由表
        publisher.publishEvent(new RefreshRoutesEvent(this));
        log.info("路由表已更新，共 {} 条：{}", definitions.size(), newIds);
    }
}
