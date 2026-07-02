package com.nexus.notification.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB Configuration — Notification Service.
 * Collections: notifications, user_notification_preferences,
 * notification_channel_config, notification_templates, notification_outbox
 */
@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = "com.nexus.notification.infrastructure.mongodb")
public class MongoConfig {
}