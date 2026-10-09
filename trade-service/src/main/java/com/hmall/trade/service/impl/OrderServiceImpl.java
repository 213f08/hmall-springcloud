package com.hmall.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.mq.RabbitMqHelper;
import com.hmall.common.utils.UserContext;
import com.hmall.trade.client.ItemClient;
import com.hmall.trade.config.TradeProperties;
import com.hmall.trade.constants.MQConstants;
import com.hmall.trade.domain.dto.ClearCartDTO;
import com.hmall.trade.domain.dto.ItemDTO;
import com.hmall.trade.domain.dto.OrderDetailDTO;
import com.hmall.trade.domain.dto.OrderFormDTO;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderDetail;
import com.hmall.trade.mapper.OrderMapper;
import com.hmall.trade.service.IOrderDetailService;
import com.hmall.trade.service.IOrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final RabbitMqHelper mqHelper;
    private final TradeProperties tradeProperties;

    @Override
    @GlobalTransactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 1.订单数据
        Order order = new Order();
        // 1.1.查询商品
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        // 1.2.获取商品id和数量的Map
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));
        Set<Long> itemIds = itemNumMap.keySet();
        // 1.3.查询商品
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }
        // 1.4.基于商品价格、购买数量计算商品总价：totalFee
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);
        // 1.5.其它属性
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(1);
        // 1.6.将Order写入数据库order表中
        save(order);

        // 2.保存订单详情
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 3.扣减库存
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }

        // 4.通知购物车服务清理商品
        // 放在全局事务的最后一步：前面任何一步失败都不会发出这条消息；
        // 发送本身失败也只记日志，不能因为一条通知把已经成功的订单回滚掉。
        // 登录用户不用写进消息体，MqUserContextConfig 会自动放进消息头
        ClearCartDTO clearCartDTO = new ClearCartDTO();
        clearCartDTO.setItemIds(itemIds);
        try {
            mqHelper.sendMessage("trade.topic", "order.create", clearCartDTO);
        } catch (Exception e) {
            log.error("清理购物车的消息发送失败，订单id：{}，用户id：{}", order.getId(), order.getUserId(), e);
        }

        // 5.发送延迟消息，到点后检查订单支付状态，超时未支付就关单还库存
        // 延迟时长取自配置 hm.trade.orderDelay（Nacos 里改，热更新，默认 15 分钟）
        try {
            int delayMillis = (int) tradeProperties.getOrderDelay().toMillis();
            mqHelper.sendDelayMessage(MQConstants.DELAY_EXCHANGE_NAME, MQConstants.DELAY_ORDER_KEY,
                    order.getId(), delayMillis);
        } catch (Exception e) {
            log.error("订单延迟消息发送失败，订单id：{}", order.getId(), e);
        }

        return order.getId();
    }

    @Override
    public void markOrderPaySuccess(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }

    @Override
    @GlobalTransactional
    public void cancelOrder(Long orderId) {
        // 1.关闭订单：把「当前状态必须是未付款」写进 where 条件，当作幂等闸门。
        //   重复消费或并发时第二次影响 0 行，直接返回，绝不会还两次库存。
        boolean cancelled = lambdaUpdate()
                .set(Order::getStatus, 5)
                .eq(Order::getId, orderId)
                .eq(Order::getStatus, 1)
                .update();
        if (!cancelled) {
            log.info("订单不存在或不是未付款状态，忽略取消操作，订单id：{}", orderId);
            return;
        }
        // 2.按订单明细恢复库存。加 @GlobalTransactional 是为了：远程恢复失败时，
        //   第 1 步的关单也一起回滚，订单留在未付款状态，消息重试时才可能重新还一次库存；
        //   否则订单已经关闭，重试进来会被幂等闸门挡掉，库存就永久丢了。
        List<OrderDetail> details = detailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list();
        if (details.isEmpty()) {
            return;
        }
        List<OrderDetailDTO> itemNumbers = details.stream()
                .map(detail -> new OrderDetailDTO()
                        .setItemId(detail.getItemId())
                        .setNum(detail.getNum()))
                .collect(Collectors.toList());
        itemClient.restoreStock(itemNumbers);
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
