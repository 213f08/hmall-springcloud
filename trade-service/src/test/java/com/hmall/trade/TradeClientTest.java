package com.hmall.trade;

import com.hmall.trade.client.CartClient;
import com.hmall.trade.client.ItemClient;
import com.hmall.trade.domain.dto.ItemDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

// 测试进程不注册到 Nacos，避免负载均衡把请求打到这个临时实例上
@SpringBootTest(properties = "spring.cloud.nacos.discovery.register-enabled=false")
public class TradeClientTest {

    @Autowired
    private ItemClient itemClient;

    @Autowired
    private CartClient cartClient;

    @Test
    void queryItemByIdsByFeign() {
        List<ItemDTO> items = itemClient.queryItemByIds(List.of(317578L));
        assertFalse(items.isEmpty(), "feign 远程调用未查到商品");
        System.out.println(">>>> trade->item 远程结果：" + items.get(0).getId()
                + " / " + items.get(0).getName()
                + " / price=" + items.get(0).getPrice()
                + " / stock=" + items.get(0).getStock());
    }

    @Test
    void removeCartByFeign() {
        cartClient.removeByItemIds(List.of(-1L));
        System.out.println(">>>> trade->cart 远程调用已到达 cart-service");
    }
}
