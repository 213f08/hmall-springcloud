package com.hmall.search.controller;

import com.hmall.search.domain.dto.ItemQueryDTO;
import com.hmall.search.domain.vo.SearchResultVO;
import com.hmall.search.service.ISearchService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Api(tags = "商品搜索相关接口")
@RestController
@RequestMapping("search")
@RequiredArgsConstructor
public class SearchController {

    private final ISearchService searchService;

    @ApiOperation("初始化索引库（建 mapping）")
    @PostMapping("/_index")
    public void createIndex() {
        searchService.createIndexIfNotExist();
    }

    @ApiOperation("多条件搜索商品")
    @GetMapping("/items")
    public SearchResultVO searchItems(ItemQueryDTO query) {
        return searchService.search(query);
    }
}
