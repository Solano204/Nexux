package com.nexus.notification.infrastructure.mongodb;

import com.nexus.notification.domain.model.NotificationDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Notification MongoDB Repository.
 *
 * Indexes (configured in MongoDB):
 * - userId (single)
 * - (userId, createdAt DESC) composite
 * - eventId (for dedup)
 * - (userId, isRead) for unread queries
 * - expiresAt (TTL index — auto-delete expired)
 * - overallStatus (delivery monitoring)
 */
@Repository
public interface NotificationRepository
        extends MongoRepository<NotificationDocument, String> {

    Page<NotificationDocument> findByUserIdOrderByCreatedAtDesc(
            String userId, Pageable pageable);

    Optional<NotificationDocument> findByNotificationIdAndUserId(
            String notificationId, String userId);

    Page<NotificationDocument> findByUserIdAndIsReadOrderByCreatedAtDesc(
            String userId, boolean isRead, Pageable pageable);

    long countByUserIdAndIsRead(String userId, boolean isRead);

    /**
     * Bulk mark all unread notifications as read for a user.
     * Uses MongoDB's updateMulti under the hood.
     */
    @Query("{'userId': ?0, 'isRead': false}")
    @Update("{'$set': {'isRead': true}}")
    long markAllReadForUser(String userId);
}