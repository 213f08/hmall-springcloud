package com.hmall.search.service;

import com.hmall.search.domain.dto.ItemQueryDTO;
import com.hmall.search.domain.vo.SearchResultVO;

import java.util.Collection;

public interface ISearchService {

    /**
     * 建 items 索引库和 mapping（已存在就跳过）
     */
    void createIndexIfNotExist();

    /**
     * 按商品 id 同步索引库：能查到且未下架就写入，查不到或已下架就删除
     */
    void handleItemChange(Collection<Long> itemIds);

    /**
     * 多条件搜索：分词匹配 + 品牌/类目/价格过滤 + 排序 + 分页 + 高亮 + 桶聚合
     */
    SearchResultVO search(ItemQueryDTO query);
}
