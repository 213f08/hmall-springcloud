package com.hmall.search.domain.vo;

import com.hmall.search.domain.dto.ItemDoc;
import lombok.Data;

/**
 * 搜索返回给前端的文档，比 ItemDoc 多一个高亮后的名字。
 */
@Data
public class ItemDocVO extends ItemDoc {

    /**
     * name 字段带 <em> 标签的高亮版本
     */
    private String highlightName;
}
