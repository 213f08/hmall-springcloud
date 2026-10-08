package com.hmall.common.mq;

import com.hmall.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

/**
 * 让 MQ 也能自动传递登录用户，业务代码继续用 UserContext.getUser()，不用把用户id写进消息体。
 * <p>
 * 发送端：给每条消息的头写入 user-info，和 Feign 那条链路用的是同一个头名。
 * 接收端：在监听容器真正调用 @RabbitListener 方法之前，把头里的用户塞回 UserContext，
 * 方法返回后再清掉——消费者线程是复用的，不清理下一条消息就会串号。
 * <p>
 * 只在 classpath 上有 spring-amqp 的服务会加载。
 */
@Slf4j
@Configuration
@ConditionalOnClass(Jackson2JsonMessageConverter.class)
@AutoConfigureBefore(RabbitAutoConfiguration.class)
public class MqUserContextConfig {

    private static final String HEADER_USER_INFO = "user-info";

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
     * 覆盖 Boot 默认的 rabbitListenerContainerFactory，给所有 @RabbitListener 挂上一条通知链。
     * 消息转换器要自己传进去，否则又会退回 JDK 序列化。
     */
    @Bean
    public RabbitListenerContainerFactory<?> rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAdviceChain((MethodInterceptor) this::restoreUserContext);
        return factory;
    }

    private Object restoreUserContext(MethodInvocation invocation) throws Throwable {
        // 通知链拦的是容器的 invokeListener(Channel, Object)，Message 在第几个参数上不固定，逐个找
        Message message = null;
        for (Object arg : invocation.getArguments()) {
            if (arg instanceof Message) {
                message = (Message) arg;
                break;
            }
        }
        Object userId = message == null ? null : message.getMessageProperties().getHeaders().get(HEADER_USER_INFO);
        if (userId == null) {
            log.debug("MQ消息没有 {} 头，跳过登录用户透传", HEADER_USER_INFO);
            return invocation.proceed();
        }
        try {
            UserContext.setUser(Long.valueOf(userId.toString()));
            return invocation.proceed();
        } finally {
            UserContext.removeUser();
        }
    }
}
