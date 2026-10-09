package com.hmall.api.client;

import com.hmall.api.dto.ItemDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 商品服务的公共客户端，目前只给搜索服务回查商品用（数据同步时按 id 拿最新数据）。
 * contextId 必须写：trade-service 里还有一个自己那套 com.hmall.trade.client.ItemClient，
 * 两个客户端都指向 item-service，不给 contextId 会因 Feign 子上下文 id 重复而启动失败。
 * <p>
 * 这里刻意不放分页查询接口：PageQuery/PageDTO 引用了 MyBatis-Plus 的 Page，
 * 而 mybatis-plus 在 hm-common 里是 provided 依赖，不会传递给没有数据库的 search-service，
 * 搜索服务一旦引用就会 NoClassDefFoundError。首次全量导入直接用 ES 的 _bulk，见 README。
 */
@FeignClient(value = "item-service", contextId = "item-api-client")
public interface ItemClient {

    @GetMapping("/items/{id}")
    ItemDTO queryItemById(@PathVariable("id") Long id);
}
