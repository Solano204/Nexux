package com.nexus.audit.write.config;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.http.HttpHost;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.elasticsearch.client.RestClient;

/**
 * Elasticsearch Configuration — Quarkus native write path.
 *
 * Produces ElasticsearchAsyncClient for non-blocking writes.
 * Uses Jackson with JavaTimeModule for Instant serialization.
 * op_type=create enforced at write time for idempotency.
 */
@ApplicationScoped
public class ElasticsearchConfig {

    @ConfigProperty(name = "elasticsearch.hosts",
            defaultValue = "http://nexus-elasticsearch:9200")
    String elasticsearchHosts;

    @Produces
    @ApplicationScoped
    public ElasticsearchAsyncClient elasticsearchAsyncClient() {
        RestClient restClient = RestClient.builder(
                HttpHost.create(elasticsearchHosts)).build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        RestClientTransport transport = new RestClientTransport(
                restClient, new JacksonJsonpMapper(mapper));

        return new ElasticsearchAsyncClient(transport);
    }
}