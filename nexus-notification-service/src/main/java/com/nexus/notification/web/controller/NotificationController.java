package com.nexus.notification.web.controller;

import com.nexus.notification.domain.exception.UnauthorizedException;
import com.nexus.notification.infrastructure.mongodb.NotificationRepository;
import com.nexus.notification.infrastructure.redis.NotificationRedisRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final NotificationRedisRepository redisRepository;

    @GetMapping
    public ResponseEntity<?> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {

        String userId = extractUserId(request);
        return ResponseEntity.ok(
                notificationRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                userId, PageRequest.of(page, size)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            HttpServletRequest request) {
        String userId = extractUserId(request);
        return ResponseEntity.ok(Map.of(
                "unreadCount",
                redisRepository.getUnreadCount(userId)));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(
            @PathVariable String notificationId,
            HttpServletRequest request) {

        String userId = extractUserId(request);
        notificationRepository
                .findByNotificationIdAndUserId(notificationId, userId)
                .ifPresent(n -> {
                    n.setRead(true);
                    n.setReadAt(java.time.Instant.now());
                    notificationRepository.save(n);
                    redisRepository.decrementUnreadCount(userId);
                });
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead(
            HttpServletRequest request) {
        String userId = extractUserId(request);
        // MongoDB bulk update
        notificationRepository
                .markAllReadForUser(userId);
        redisRepository.resetUnreadCount(userId);
        return ResponseEntity.ok().build();
    }

    private String extractUserId(HttpServletRequest request) {
        String userId = request.getHeader("X-User-Id");
        if (userId == null)
            throw new UnauthorizedException("Authentication required");
        return userId;
    }
}