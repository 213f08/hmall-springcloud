package com.hmall.cart;

import com.hmall.cart.client.ItemClient;
import com.hmall.cart.domain.dto.ItemDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest
public class ItemClientTest {

    @Autowired
    private ItemClient itemClient;

    @Test
    void queryItemByIdsByFeign() {
        List<ItemDTO> items = itemClient.queryItemByIds(List.of(317578L));
        assertFalse(items.isEmpty(), "feign 远程调用未查到商品");
        System.out.println(">>>> OpenFeign 远程调用结果：" + items.get(0).getId()
                + " / " + items.get(0).getName()
                + " / price=" + items.get(0).getPrice()
                + " / stock=" + items.get(0).getStock());
    }
}
