package com.hmall.common.mq;

import com.hmall.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * 让 MQ 也能自动传递登录用户，业务代码继续用 UserContext.getUser()，不用把用户id写进消息体。
 * <p>
 * 发送端：给 RabbitTemplate 加一个发送前置处理器，把当前用户写进消息头 user-info，
 * 和 Feign 那条链路用的是同一个头名。
 * 接收端：消息刚收到、还没转成对象时，把头里的用户塞回 UserContext；头里没有就清掉，
 * 避免消费者线程复用时串号（处理一条消息只在这条消息的线程上生效）。
 * <p>
 * 只在 classpath 上有 spring-amqp 的服务会加载。
 */
@Slf4j
@Configuration
@ConditionalOnClass(Jackson2JsonMessageConverter.class)
@AutoConfigureBefore(RabbitAutoConfiguration.class)
public class MqUserContextConfig {

    public static final String HEADER_USER_INFO = "user-info";

    private final RabbitTemplate rabbitTemplate;

    public MqUserContextConfig(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void addUserContextToMessageHeader() {
        rabbitTemplate.addBeforePublishPostProcessors(message -> {
            Long userId = UserContext.getUser();
            if (userId != null) {
                message.getMessageProperties().setHeader(HEADER_USER_INFO, userId.toString());
            }
            return message;
        });
    }

    /**
     * 覆盖 Boot 默认的 rabbitListenerContainerFactory，只为了挂一个消息后置处理器。
     * 关键：必须先让 Boot 的 configurer 把工厂配好，否则 spring.rabbitmq.listener.simple.*
     * 里的消费者重试、prefetch、ack 模式、消息转换器全都会被丢掉。
     * afterReceivePostProcessors 和重试用的 adviceChain 是两个互不相干的字段，所以能只加不改。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAfterReceivePostProcessors(this::restoreUserContext);
        return factory;
    }

    private Message restoreUserContext(Message message) {
        Object userId = message.getMessageProperties().getHeaders().get(HEADER_USER_INFO);
        if (userId == null) {
            UserContext.removeUser();
        } else {
            UserContext.setUser(Long.valueOf(userId.toString()));
        }
        return message;
    }
}
