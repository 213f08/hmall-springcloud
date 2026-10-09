package com.hmall.search.listener;

import com.hmall.search.constants.SearchConstants;
import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 监听商品服务的增删改通知，同步 ES 索引库（作业 6.3）。
 * 消息里只带 id，具体数据回查 MySQL，避免消息内容和真实状态不一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItemListener {

    private final ISearchService searchService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = SearchConstants.ITEM_QUEUE, durable = "true"),
            exchange = @Exchange(name = SearchConstants.ITEM_EXCHANGE),
            key = SearchConstants.ITEM_CHANGE_KEY
    ))
    public void listenItemChange(Collection<Long> itemIds) {
        log.info("收到商品变更消息，商品id：{}", itemIds);
        searchService.handleItemChange(itemIds);
    }
}
