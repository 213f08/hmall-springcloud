package com.hmall.common.config;

import com.hmall.common.mq.RabbitMqHelper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringAMQP 默认用 JDK 序列化（ObjectOutputStream），消息在控制台里是一堆乱码字节，
 * 而且消费方必须有同一个类才能反序列化。换成 JSON 之后消息可读，跨语言也能用。
 * 只在 classpath 上有 spring-amqp 的服务会加载，item、user、gateway 不受影响。
 */
@Configuration
@ConditionalOnClass(Jackson2JsonMessageConverter.class)
public class AmqpConfig {

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitMqHelper rabbitMqHelper(RabbitTemplate rabbitTemplate) {
        return new RabbitMqHelper(rabbitTemplate);
    }
}
