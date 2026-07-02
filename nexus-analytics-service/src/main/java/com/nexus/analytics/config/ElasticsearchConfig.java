package com.nexus.analytics.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Elasticsearch Configuration — Analytics Service.
 *
 * Indices:
 * - nexus-analytics-user-{year}-{month}: per-user analytics (routing=userId)
 * - nexus-analytics-platform-{year}-{month}: platform aggregations
 * - nexus-analytics-anomalies: spending anomalies (no rotation)
 *
 * Time-series optimization: hot indices in memory, warm memory-mapped,
 * cold compressed on disk.
 */
@Configuration
public class ElasticsearchConfig {

    @Value("${spring.data.elasticsearch.uris:http://nexus-elasticsearch:9200}")
    private String elasticsearchUri;

    @Bean
    public ElasticsearchClient elasticsearchClient(ObjectMapper objectMapper) {
        RestClient restClient = RestClient.builder(
                HttpHost.create(elasticsearchUri)).build();
        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper(objectMapper));
        return new ElasticsearchClient(transport);
    }
}