package com.hmall.cart.config;

import com.hmall.cart.client.ItemClient;
import com.hmall.cart.domain.dto.ItemDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ItemClientFallbackFactory implements FallbackFactory<ItemClient> {
    @Override
    public ItemClient create(Throwable throwable) {
        return new ItemClient() {
            @Override
            public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
                // 记录异常信息，返回空集合而不是把异常抛给调用者
                log.error("查询商品失败", throwable);
                return Collections.emptyList();
            }
        };
    }
}
