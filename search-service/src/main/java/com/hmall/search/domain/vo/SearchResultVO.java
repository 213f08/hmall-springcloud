package com.hmall.search.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 搜索结果：分页数据 + 侧边栏筛选项。
 * brands/categories 是对"当前命中的全部文档"做桶聚合得到的，
 * 所以用户勾选品牌后，侧边栏的可选值会跟着其他条件变，而不是写死的全量品牌。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SearchResultVO extends PageVO<ItemDocVO> {

    private List<String> brands;

    private List<String> categories;

    public SearchResultVO(Long total, Long pages, List<ItemDocVO> list) {
        super(total, pages, list);
    }
}
