package com.hmall.item.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmall.common.domain.PageDTO;
import com.hmall.common.domain.PageQuery;
import com.hmall.common.mq.RabbitMqHelper;
import com.hmall.common.utils.BeanUtils;
import com.hmall.item.constants.ItemMQConstants;
import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;
import com.hmall.item.service.IItemService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@Api(tags = "商品管理相关接口")
@RestController
@RequestMapping("/items")
@RequiredArgsConstructor
public class ItemController {

    private final IItemService itemService;

    private final RabbitMqHelper mqHelper;

    @ApiOperation("分页查询商品")
    @GetMapping("/page")
    public PageDTO<ItemDTO> queryItemByPage(PageQuery query) {
        // 1.分页查询
        Page<Item> result = itemService.page(query.toMpPage("update_time", false));
        // 2.封装并返回
        return PageDTO.of(result, ItemDTO.class);
    }

    @ApiOperation("根据id批量查询商品")
    @GetMapping
    public List<ItemDTO> queryItemByIds(@RequestParam("ids") List<Long> ids){
        return itemService.queryItemByIds(ids);
    }

    @ApiOperation("根据id查询商品")
    @GetMapping("{id}")
    public ItemDTO queryItemById(@PathVariable("id") Long id) {
        return BeanUtils.copyBean(itemService.getById(id), ItemDTO.class);
    }

    @ApiOperation("新增商品")
    @PostMapping
    public void saveItem(@RequestBody ItemDTO item) {
        // 新增
        Item entity = BeanUtils.copyBean(item, Item.class);
        itemService.save(entity);
        // 必须用回填后的自增主键发消息：直接发前端传来的 id 会让消费者查不到商品
        notifyIndexChange(entity.getId());
    }

    @ApiOperation("更新商品状态")
    @PutMapping("/status/{id}/{status}")
    public void updateItemStatus(@PathVariable("id") Long id, @PathVariable("status") Integer status){
        Item item = new Item();
        item.setId(id);
        item.setStatus(status);
        itemService.updateById(item);
        // 下架（status != 1）的商品要能从索引库里删掉，交给消费者按最新状态判断
        notifyIndexChange(id);
    }

    @ApiOperation("更新商品")
    @PutMapping
    public void updateItem(@RequestBody ItemDTO item) {
        // 不允许修改商品状态，所以强制设置为null，更新时，就会忽略该字段
        item.setStatus(null);
        // 更新
        itemService.updateById(BeanUtils.copyBean(item, Item.class));
        notifyIndexChange(item.getId());
    }

    @ApiOperation("根据id删除商品")
    @DeleteMapping("{id}")
    public void deleteItemById(@PathVariable("id") Long id) {
        itemService.removeById(id);
        notifyIndexChange(id);
    }

    /**
     * 库存变更（扣减/恢复）不用通知：ES 文档里没有 stock 字段，库存以 MySQL 为准。
     */
    private void notifyIndexChange(Long id) {
        if (id == null) {
            return;
        }
        mqHelper.sendMessage(ItemMQConstants.ITEM_EXCHANGE, ItemMQConstants.ITEM_CHANGE_KEY,
                Collections.singletonList(id));
    }

    @ApiOperation("批量扣减库存")
    @PutMapping("/stock/deduct")
    public void deductStock(@RequestBody List<OrderDetailDTO> items){
        itemService.deductStock(items);
    }

    @ApiOperation("批量恢复库存")
    @PutMapping("/stock/restore")
    public void restoreStock(@RequestBody List<OrderDetailDTO> items){
        itemService.restoreStock(items);
    }
}
