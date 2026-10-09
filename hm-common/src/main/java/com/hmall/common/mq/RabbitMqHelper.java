package com.hmall.common.mq;

import com.hmall.common.exception.BizIllegalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.concurrent.TimeUnit;

/**
 * RabbitMQ 发送消息的统一入口，业务代码不要再直接注入 RabbitTemplate。
 * <p>
 * 三种发送方式对应三种可靠性要求：
 * 1）sendMessage：发完就走，broker 收没收到不管（day06 那种「通知一下」的场景）
 * 2）sendDelayMessage：延迟消息，需要 broker 装了 rabbitmq_delayed_message_exchange 插件
 * 3）sendMessageWithConfirm：等 broker 回确认，没确认就重试，重试耗尽直接抛异常
 * 第 3 种要生效，Nacos 的 shared-mq.yaml 里必须开 spring.rabbitmq.publisher-confirm-type: correlated。
 */
@Slf4j
public class RabbitMqHelper {

    private static final long CONFIRM_TIMEOUT_SECONDS = 3;

    private final RabbitTemplate rabbitTemplate;

    public RabbitMqHelper(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendMessage(String exchange, String routingKey, Object msg) {
        rabbitTemplate.convertAndSend(exchange, routingKey, msg);
    }

    public void sendDelayMessage(String exchange, String routingKey, Object msg, int delay) {
        rabbitTemplate.convertAndSend(exchange, routingKey, msg, message -> {
            message.getMessageProperties().setDelay(delay);
            return message;
        });
    }

    public void sendMessageWithConfirm(String exchange, String routingKey, Object msg, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            CorrelationData correlation = new CorrelationData(exchange + ":" + routingKey + ":" + attempt);
            rabbitTemplate.convertAndSend(exchange, routingKey, msg, correlation);
            if (waitConfirm(correlation, exchange, routingKey, attempt)) {
                return;
            }
        }
        throw new BizIllegalException("消息发送失败，交换机：" + exchange + "，RoutingKey：" + routingKey);
    }

    private boolean waitConfirm(CorrelationData correlation, String exchange, String routingKey, int attempt) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm != null && confirm.isAck()) {
                return true;
            }
            log.warn("消息未收到 broker 确认，第 {} 次，交换机：{}，RoutingKey：{}，原因：{}",
                    attempt, exchange, routingKey, confirm == null ? "无确认返回" : confirm.getReason());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待消息确认被中断，第 {} 次，交换机：{}，RoutingKey：{}", attempt, exchange, routingKey);
        } catch (Exception e) {
            log.warn("等待消息确认超时或异常，第 {} 次，交换机：{}，RoutingKey：{}：{}",
                    attempt, exchange, routingKey, e.getMessage());
        }
        return false;
    }
}
