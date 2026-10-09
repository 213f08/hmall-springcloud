package com.hmall.search.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@ApiModel(description = "商品搜索条件")
public class ItemQueryDTO {

    @ApiModelProperty("关键字，对商品名做分词匹配")
    private String keyword;

    @ApiModelProperty("品牌，多选，同一字段内是或的关系")
    private List<String> brand;

    @ApiModelProperty("类目，多选，同一字段内是或的关系")
    private List<String> category;

    @ApiModelProperty("最低价（分）")
    private Integer priceMin;

    @ApiModelProperty("最高价（分）")
    private Integer priceMax;

    @ApiModelProperty("排序方式：1-综合，2-销量降，3-评论数降，4-价格升，5-价格降")
    private Integer sortType;

    @ApiModelProperty("页码，从1开始")
    private Integer pageNo = 1;

    @ApiModelProperty("每页条数")
    private Integer pageSize = 20;
}
