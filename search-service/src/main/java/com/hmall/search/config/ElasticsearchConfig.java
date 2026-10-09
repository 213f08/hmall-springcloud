package com.hmall.search.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ElasticsearchConfig {

    /**
     * ES 的高级客户端。地址走配置，默认 localhost:9200；
     * 本机因为 cpolar 占了 9200，所以 application-local.yaml 里覆盖成 9201。
     */
    @Bean(destroyMethod = "close")
    public RestHighLevelClient restHighLevelClient(
            @Value("${hm.es.host:localhost}") String host,
            @Value("${hm.es.port:9200}") int port) {
        return new RestHighLevelClient(RestClient.builder(new HttpHost(host, port, "http")));
    }
}
