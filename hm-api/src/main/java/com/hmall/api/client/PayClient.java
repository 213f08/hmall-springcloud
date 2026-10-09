package com.hmall.api.client;

import com.hmall.api.dto.PayOrderDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 故意不配 fallbackFactory：查支付流水失败时必须抛异常，让消息重试、最终落到错误队列。
 * 如果降级成返回 null，调用方就分不清「确实没有支付流水（该关单）」和
 * 「支付服务此刻连不上（不该关单）」，会把已付款的订单误关掉。
 */
@FeignClient(value = "pay-service")
public interface PayClient {
    /**
     * 根据交易订单id查询支付单
     * @param id 业务订单id
     * @return 支付单信息，没有支付流水时返回 null
     */
    @GetMapping("/pay-orders/biz/{id}")
    PayOrderDTO queryPayOrderByBizOrderNo(@PathVariable("id") Long id);
}
