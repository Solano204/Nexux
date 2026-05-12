package com.nexus.identity.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "sessionId")
public class Session {

    @Id
    @Column(name = "session_id", updatable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private UUID jti;

    @Column(name = "device_fingerprint")
    private String deviceFingerprint;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "is_active")
    private boolean isActive;

    @Column(name = "refresh_token_hash")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String refreshTokenHash;

    @PrePersist
    void prePersist() {
        if (sessionId == null) sessionId = UUID.randomUUID();
        if (issuedAt == null) issuedAt = Instant.now();
        if (lastActivityAt == null) lastActivityAt = Instant.now();
        isActive = true;
    }

    public void deactivate() {
        this.isActive = false;
    }

    public void refreshActivity() {
        this.lastActivityAt = Instant.now();
    }
}