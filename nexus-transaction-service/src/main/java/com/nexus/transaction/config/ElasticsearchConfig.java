package com.nexus.transaction.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;

@Configuration
@EnableElasticsearchRepositories(basePackages = "com.nexus.transaction.infrastructure.elasticsearch")
public class ElasticsearchConfig {
}