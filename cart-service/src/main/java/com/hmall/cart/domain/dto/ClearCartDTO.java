package com.hmall.cart.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.Set;

/**
 * 接收 trade-service 发来的「清理购物车」消息。
 * 字段和 trade-service 的 com.hmall.trade.domain.dto.ClearCartDTO 一一对应，两边各留一份，不共享 jar。
 */
@Data
@ApiModel(description = "清理购物车消息")
public class ClearCartDTO {
    @ApiModelProperty("要清理的商品id")
    private Set<Long> itemIds;
}
