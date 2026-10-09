package com.hmall.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

import java.nio.charset.StandardCharsets;

/**
 * 消费本服务的错误队列，把「重试耗尽仍失败」的消息完整打到日志里，让失败可见、可报警。
 * <p>
 * 注意这是「让问题被看见」，不是「自动补偿」：消息一旦被消费就离开了队列，
 * 真要自动重放需要幂等的补偿任务或本地消息表，超出课程范围。不想让它自动消费掉的话，
 * 去掉 {@link MqConsumeErrorAutoConfiguration} 里这个 Bean 即可，消息会留在队列里用管理台查。
 */
@Slf4j
public class MqErrorQueueListener {

    @RabbitListener(queues = "#{errorQueue.name}")
    public void listenErrorQueue(Message message) {
        // 这里绝对不能把异常抛出去：本方法也挂在「失败重试 + 重投错误队列」的拦截器链上，
        // 一旦抛出，消息会被再投回 error.direct，形成无限循环刷日志。
        try {
            log.error("【MQ消费失败已重试耗尽，需人工介入】原交换机={}，原RoutingKey={}，异常={}，消息体={}",
                    message.getMessageProperties().getHeaders().get("x-original-exchange"),
                    message.getMessageProperties().getHeaders().get("x-original-routingKey"),
                    message.getMessageProperties().getHeaders().get("x-exception-message"),
                    new String(message.getBody(), StandardCharsets.UTF_8));
        } catch (Throwable e) {
            log.error("处理错误队列消息时又发生异常", e);
        }
    }
}
