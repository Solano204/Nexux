package com.nexus.notification.web.controller;

import com.nexus.notification.domain.exception.UnauthorizedException;
import com.nexus.notification.infrastructure.mongodb.NotificationRepository;
import com.nexus.notification.infrastructure.redis.NotificationRedisRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Notifications", description = "In-app notification feed for the caller's own user — read status and unread count.")
@SecurityRequirement(name = "X-User-Id")
public class NotificationController {

    private final NotificationRepository notificationRepository;
    private final NotificationRedisRepository redisRepository;

    @Operation(summary = "List my notifications", description = "Paginated (manual page/size query params), newest first.")
    @ApiResponse(responseCode = "200", description = "Notifications retrieved")
    @GetMapping
    public ResponseEntity<?> getNotifications(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request) {

        String userId = extractUserId(request);
        return ResponseEntity.ok(
                notificationRepository
                        .findByUserIdOrderByCreatedAtDesc(
                                userId, PageRequest.of(page, size)));
    }

    @Operation(summary = "Get my unread notification count", description = "Fast Redis-backed counter — for badge displays, avoids scanning the full notification collection.")
    @ApiResponse(responseCode = "200", description = "Count retrieved")
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(
            HttpServletRequest request) {
        String userId = extractUserId(request);
        return ResponseEntity.ok(Map.of(
                "unreadCount",
                redisRepository.getUnreadCount(userId)));
    }

    @Operation(summary = "Mark a notification as read", description = "Idempotent no-op if the notification doesn't exist or belongs to another user — returns 200 either way rather than 404, since \"already in the desired state\" isn't an error here.")
    @ApiResponse(responseCode = "200", description = "Marked as read (or already was / didn't exist)")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(
            @Parameter(description = "Notification ID", required = true)
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

    @Operation(summary = "Mark all my notifications as read", description = "Bulk update — resets the unread counter to 0.")
    @ApiResponse(responseCode = "200", description = "All marked as read")
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