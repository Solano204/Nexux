package com.nexus.identity.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * PasswordHistory — stores BCrypt hashes of previous passwords.
 *
 * Prevents reuse of the last 5 passwords (financial security requirement).
 * Only the hash is stored — never the plaintext password.
 *
 * UserCommandService queries the 5 most recent entries per userId
 * and checks each with BCrypt.matches() before allowing a new password.
 */
@Entity
@Table(name = "password_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "historyId")
public class PasswordHistory {

    @Id
    @Column(name = "history_id", updatable = false)
    private UUID historyId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * BCrypt hash of the historical password.
     * Excluded from toString/serialization — never log password hashes.
     */
    @Column(name = "password_hash", nullable = false, length = 72)
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ToString.Exclude
    private String passwordHash;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (historyId == null) historyId = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}