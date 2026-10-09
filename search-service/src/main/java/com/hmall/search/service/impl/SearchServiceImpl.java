package com.hmall.search.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmall.api.client.ItemClient;
import com.hmall.api.dto.ItemDTO;
import com.hmall.common.utils.CollUtils;
import com.hmall.search.domain.dto.ItemDoc;
import com.hmall.search.domain.dto.ItemQueryDTO;
import com.hmall.search.domain.vo.ItemDocVO;
import com.hmall.search.domain.vo.SearchResultVO;
import com.hmall.search.service.ISearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmall.search.constants.SearchConstants.ITEM_INDEX;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements ISearchService {

    /**
     * name 用 ik_max_word 分词（细粒度，写入时切得越全，检索越容易命中），
     * 同时挂一个 keyword 子字段，给精确匹配、排序、聚合用。
     */
    private static final String ITEM_MAPPING = "{\n"
            + "  \"properties\": {\n"
            + "    \"id\": {\"type\": \"long\"},\n"
            + "    \"name\": {\"type\": \"text\", \"analyzer\": \"ik_max_word\","
            + " \"fields\": {\"keyword\": {\"type\": \"keyword\", \"ignore_above\": 256}}},\n"
            + "    \"price\": {\"type\": \"integer\"},\n"
            + "    \"image\": {\"type\": \"keyword\", \"index\": false},\n"
            + "    \"category\": {\"type\": \"keyword\"},\n"
            + "    \"brand\": {\"type\": \"keyword\"},\n"
            + "    \"spec\": {\"type\": \"keyword\"},\n"
            + "    \"sold\": {\"type\": \"integer\"},\n"
            + "    \"commentCount\": {\"type\": \"integer\"},\n"
            + "    \"isAD\": {\"type\": \"boolean\"}\n"
            + "  }\n"
            + "}";

    private final RestHighLevelClient client;
    private final ItemClient itemClient;

    @Override
    public void createIndexIfNotExist() {
        try {
            boolean exists = client.indices()
                    .exists(new GetIndexRequest(ITEM_INDEX), RequestOptions.DEFAULT);
            if (exists) {
                log.info("索引库 {} 已存在，跳过创建", ITEM_INDEX);
                return;
            }
            CreateIndexRequest request = new CreateIndexRequest(ITEM_INDEX);
            request.mapping(ITEM_MAPPING, XContentType.JSON);
            client.indices().create(request, RequestOptions.DEFAULT);
            log.info("索引库 {} 创建完成", ITEM_INDEX);
        } catch (IOException e) {
            throw new RuntimeException("创建索引库失败", e);
        }
    }

    private void bulkIndex(List<ItemDTO> items) {
        BulkRequest request = new BulkRequest();
        for (ItemDTO item : items) {
            request.add(new IndexRequest(ITEM_INDEX)
                    .id(item.getId().toString())
                    .source(JSONUtil.toJsonStr(toDoc(item)), XContentType.JSON));
        }
        try {
            BulkResponse response = client.bulk(request, RequestOptions.DEFAULT);
            if (response.hasFailures()) {
                log.error("批量写入索引库存在失败：{}", response.buildFailureMessage());
            }
        } catch (IOException e) {
            throw new RuntimeException("批量导入文档失败", e);
        }
    }

    @Override
    public void handleItemChange(Collection<Long> itemIds) {
        if (CollUtils.isEmpty(itemIds)) {
            return;
        }
        List<ItemDTO> toSave = new ArrayList<>(itemIds.size());
        List<Long> toDelete = new ArrayList<>(itemIds.size());
        for (Long id : itemIds) {
            // 回查 MySQL 拿最新数据，而不是相信消息里的内容
            ItemDTO item = itemClient.queryItemById(id);
            if (item == null || item.getStatus() == null || item.getStatus() != 1) {
                toDelete.add(id);
            } else {
                toSave.add(item);
            }
        }
        if (!toSave.isEmpty()) {
            bulkIndex(toSave);
        }
        if (!toDelete.isEmpty()) {
            BulkRequest request = new BulkRequest();
            for (Long id : toDelete) {
                request.add(new DeleteRequest(ITEM_INDEX, id.toString()));
            }
            try {
                client.bulk(request, RequestOptions.DEFAULT);
            } catch (IOException e) {
                throw new RuntimeException("删除索引库文档失败", e);
            }
        }
        log.info("同步索引库完成：写入 {} 条，删除 {} 条", toSave.size(), toDelete.size());
    }

    @Override
    public SearchResultVO search(ItemQueryDTO query) {
        int pageNo = query.getPageNo() == null || query.getPageNo() < 1 ? 1 : query.getPageNo();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : query.getPageSize();

        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.query(buildQuery(query));
        sourceBuilder.from((pageNo - 1) * pageSize);
        sourceBuilder.size(pageSize);
        // ES 默认最多只统计到 10000 条，这里要求统计真实总数，否则分页页数不准
        sourceBuilder.trackTotalHits(true);
        sourceBuilder.highlighter(new HighlightBuilder()
                .field("name").preTags("<em>").postTags("</em>"));
        applySort(sourceBuilder, query.getSortType());
        // 桶聚合：给筛选侧边栏提供当前结果集里可选的品牌和类目
        sourceBuilder.aggregation(AggregationBuilders.terms("brand_agg").field("brand").size(20));
        sourceBuilder.aggregation(AggregationBuilders.terms("category_agg").field("category").size(20));

        SearchRequest request = new SearchRequest(ITEM_INDEX);
        request.indicesOptions(IndicesOptions.LENIENT_EXPAND_OPEN);
        request.source(sourceBuilder);
        try {
            SearchResponse response = client.search(request, RequestOptions.DEFAULT);
            long total = response.getHits().getTotalHits().value;
            List<ItemDocVO> list = new ArrayList<>(pageSize);
            for (SearchHit hit : response.getHits().getHits()) {
                list.add(toVOWithHighlight(hit));
            }
            SearchResultVO result = new SearchResultVO(total,
                    (long) Math.ceil((double) total / pageSize), list);
            Aggregations aggregations = response.getAggregations();
            if (aggregations != null) {
                result.setBrands(bucketKeys(aggregations.get("brand_agg")));
                result.setCategories(bucketKeys(aggregations.get("category_agg")));
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("搜索失败", e);
        }
    }

    /**
     * 关键字放 must（要参与相关性打分），品牌、类目、价格放 filter（只筛选不打分，结果还能被 ES 缓存）。
     * 同一个字段的多选是"或"，不同字段之间是"与"。
     */
    private BoolQueryBuilder buildQuery(ItemQueryDTO query) {
        BoolQueryBuilder bool = QueryBuilders.boolQuery();
        if (StrUtil.isNotBlank(query.getKeyword())) {
            bool.must(QueryBuilders.matchQuery("name", query.getKeyword()));
        }
        if (CollUtils.isNotEmpty(query.getBrand())) {
            bool.filter(QueryBuilders.termsQuery("brand", query.getBrand()));
        }
        if (CollUtils.isNotEmpty(query.getCategory())) {
            bool.filter(QueryBuilders.termsQuery("category", query.getCategory()));
        }
        if (query.getPriceMin() != null || query.getPriceMax() != null) {
            RangeQueryBuilder price = QueryBuilders.rangeQuery("price");
            if (query.getPriceMin() != null) {
                price.gte(query.getPriceMin());
            }
            if (query.getPriceMax() != null) {
                price.lte(query.getPriceMax());
            }
            bool.filter(price);
        }
        return bool;
    }

    private void applySort(SearchSourceBuilder source, Integer sortType) {
        int type = sortType == null ? 1 : sortType;
        switch (type) {
            case 2:
                // 销量相同按价格升序，避免翻页时顺序抖动导致重复或漏项
                source.sort("sold", SortOrder.DESC).sort("price", SortOrder.ASC);
                break;
            case 3:
                source.sort("commentCount", SortOrder.DESC).sort("price", SortOrder.ASC);
                break;
            case 4:
                source.sort("price", SortOrder.ASC);
                break;
            case 5:
                source.sort("price", SortOrder.DESC);
                break;
            default:
                // 综合：先按相关性打分，打分相同按价格
                source.sort(SortBuilders.scoreSort().order(SortOrder.DESC))
                        .sort("price", SortOrder.ASC);
        }
    }

    private List<String> bucketKeys(Terms terms) {
        if (terms == null) {
            return CollUtils.emptyList();
        }
        return terms.getBuckets().stream().map(Terms.Bucket::getKeyAsString).collect(Collectors.toList());
    }

    private ItemDocVO toVOWithHighlight(SearchHit hit) {
        ItemDocVO vo = JSONUtil.toBean(hit.getSourceAsString(), ItemDocVO.class);
        HighlightField name = hit.getHighlightFields().get("name");
        vo.setHighlightName(name == null || name.getFragments() == null || name.getFragments().length == 0
                ? vo.getName() : name.getFragments()[0].string());
        return vo;
    }

    /**
     * 当前索引库里的文档数，用来和 MySQL 的上架商品数对账
     */
    public long countDocs() {
        try {
            CountResponse response = client.count(new CountRequest(ITEM_INDEX), RequestOptions.DEFAULT);
            return response.getCount();
        } catch (IOException e) {
            throw new RuntimeException("统计索引库文档数失败", e);
        }
    }

    private ItemDoc toDoc(ItemDTO item) {
        ItemDoc doc = new ItemDoc();
        doc.setId(item.getId());
        doc.setName(item.getName());
        doc.setPrice(item.getPrice());
        doc.setImage(item.getImage());
        doc.setCategory(item.getCategory());
        doc.setBrand(item.getBrand());
        doc.setSpec(item.getSpec());
        doc.setSold(item.getSold());
        doc.setCommentCount(item.getCommentCount());
        doc.setIsAD(item.getIsAD());
        return doc;
    }
}
