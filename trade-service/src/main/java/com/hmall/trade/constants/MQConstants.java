package com.hmall.trade.constants;

/**
 * 交易服务用到的 MQ 拓扑常量。
 * 交换机、队列、RoutingKey 在发送方和接收方都要用到，写死两处容易改漏，统一放这里。
 */
public interface MQConstants {
    String DELAY_EXCHANGE_NAME = "trade.delay.direct";
    String DELAY_ORDER_QUEUE_NAME = "trade.delay.order.queue";
    String DELAY_ORDER_KEY = "delay.order.query";
}
