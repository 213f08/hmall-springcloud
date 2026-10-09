package com.hmall.common.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费者重试耗尽后，把失败消息另投到「自己的错误队列」，方便人工排查或补偿，
 * 而不是像默认那样直接丢弃或者无限重入队刷日志。
 * <p>
 * 只在显式打开了消费者重试时才装配：spring.rabbitmq.listener.simple.retry.enabled=true。
 * Boot 的 RabbitAnnotationDrivenConfiguration 会把容器里唯一的 MessageRecoverer 交给重试拦截器，
 * 所以这里只需要声明这个 Bean，不需要自己去拼 advice 链。
 * <p>
 * 队列名和绑定的 RoutingKey 都用当前微服务名，这样每个服务自己的失败消息落在自己的队列里，不会混。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Jackson2JsonMessageConverter.class)
@ConditionalOnProperty(name = "spring.rabbitmq.listener.simple.retry.enabled", havingValue = "true")
public class MqConsumeErrorAutoConfiguration {

    public static final String ERROR_EXCHANGE_NAME = "error.direct";

    @Bean
    public DirectExchange errorExchange() {
        return new DirectExchange(ERROR_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue errorQueue(@Value("${spring.application.name}") String applicationName) {
        return new Queue(applicationName + "error.queue", true);
    }

    @Bean
    public Binding errorBinding(Queue errorQueue, DirectExchange errorExchange,
                                @Value("${spring.application.name}") String applicationName) {
        return BindingBuilder.bind(errorQueue).to(errorExchange).with(applicationName);
    }

    @Bean
    public MessageRecoverer messageRecoverer(RabbitTemplate rabbitTemplate,
                                             @Value("${spring.application.name}") String applicationName) {
        return new RepublishMessageRecoverer(rabbitTemplate, ERROR_EXCHANGE_NAME, applicationName);
    }

    @Bean
    public MqErrorQueueListener mqErrorQueueListener() {
        return new MqErrorQueueListener();
    }
}
