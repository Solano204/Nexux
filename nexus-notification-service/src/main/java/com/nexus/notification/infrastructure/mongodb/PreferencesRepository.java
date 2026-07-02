package com.nexus.notification.infrastructure.mongodb;

import com.nexus.notification.domain.model.UserNotificationPreferences;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * User Notification Preferences MongoDB Repository.
 *
 * Document ID = userId (one preferences doc per user).
 * Cached in Redis with 5-minute TTL for hot-path reads.
 */
@Repository
public interface PreferencesRepository
        extends MongoRepository<UserNotificationPreferences, String> {

    Optional<UserNotificationPreferences> findByUserId(String userId);

    List<UserNotificationPreferences> findByGlobalOptOutFalse();
}