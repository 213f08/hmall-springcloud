package com.hmall.search.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * ES 里的商品文档。
 * 注意这里没有 stock（库存）字段：库存要实时准确，走 MySQL；
 * ES 只负责搜索和展示用的字段，这也意味着库存变化不需要同步索引库。
 */
@Data
public class ItemDoc {

    private Long id;

    private String name;

    private Integer price;

    private String image;

    private String category;

    private String brand;

    private String spec;

    private Integer sold;

    @JsonProperty("commentCount")
    private Integer commentCount;

    @JsonProperty("isAD")
    private Boolean isAD;
}
