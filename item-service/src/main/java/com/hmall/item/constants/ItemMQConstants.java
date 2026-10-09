package com.hmall.item.constants;

/**
 * 商品变更通知的 MQ 拓扑。
 * 这三个字符串必须和 search-service 里 SearchConstants 的一致——
 * 交换机/队列/RoutingKey 是两边约定，改一边就断了。
 */
public interface ItemMQConstants {
    String ITEM_EXCHANGE = "item.direct";
    String ITEM_CHANGE_KEY = "item.change";
}
