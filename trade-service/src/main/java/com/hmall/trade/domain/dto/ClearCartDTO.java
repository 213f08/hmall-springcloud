package com.hmall.trade.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Set;

/**
 * 发给购物车服务的「清理购物车」消息体。
 * cart-service 侧有一份字段完全一样的同名类，两边靠 JSON 的字段名对齐，不共享 jar。
 */
@Data
@ApiModel(description = "清理购物车消息")
public class ClearCartDTO {
    @ApiModelProperty("要清理的商品id")
    private Set<Long> itemIds;
}
