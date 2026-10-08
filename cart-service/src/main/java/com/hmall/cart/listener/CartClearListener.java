package com.hmall.cart.listener;

import com.hmall.cart.domain.dto.ClearCartDTO;
import com.hmall.cart.service.ICartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 监听交易服务发出的「下单成功」消息，清理该用户购物车里对应的商品。
 * trade.topic 是 topic 类型交换机，必须显式写 type，@Exchange 默认是 direct。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartClearListener {

    private final ICartService cartService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cart.clear.queue", durable = "true"),
            exchange = @Exchange(name = "trade.topic", type = ExchangeTypes.TOPIC),
            key = "order.create"
    ))
    public void listenCartClear(ClearCartDTO message) {
        log.info("收到清理购物车消息：{}", message);
        // 登录用户由 hm-common 的 MqUserContextConfig 从消息头恢复到 UserContext，这里直接调用即可
        cartService.removeByItemIds(message.getItemIds());
    }
}
