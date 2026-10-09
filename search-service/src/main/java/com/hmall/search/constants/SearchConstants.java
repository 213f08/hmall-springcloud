package com.hmall.search.constants;

/**
 * 搜索服务用到的索引库名和 MQ 拓扑常量。
 */
public interface SearchConstants {

    /** 商品索引库名 */
    String ITEM_INDEX = "items";

    /** 商品服务变更通知用的交换机、队列、RoutingKey */
    String ITEM_EXCHANGE = "item.direct";
    String ITEM_QUEUE = "search.item.change.queue";
    String ITEM_CHANGE_KEY = "item.change";
}
