package com.nexus.identity.domain.model;

import com.nexus.identity.domain.model.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * User — Core domain aggregate for identity management.
 *
 * Design decisions:
 * - UUID v7 (time-sortable) for better B-tree index locality
 * - @Version for JPA optimistic locking (prevents lost updates)
 * - @ToString.Exclude on passwordHash (NEVER log passwords)
 * - @JsonIgnore on passwordHash (NEVER serialize passwords)
 * - Soft-delete via deletedAt (regulatory 7-year retention)
 * - roles as TEXT[] PostgreSQL array (multiple roles per user)
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"passwordHash"})
@EqualsAndHashCode(of = "userId")
public class User {

    @Id
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    @Column(name = "password_hash", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(nullable = false, length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> roles;

    @Column(name = "failed_login_attempts")
    private int failedLoginAttempts;

    @Column(name = "lock_until")
    private Instant lockUntil;

    @Column(name = "kyc_verified_at")
    private Instant kycVerifiedAt;

    @Column(name = "kyc_next_review_at")
    private Instant kycNextReviewAt;

    @Version
    private int version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    void prePersist() {
        if (userId == null) {
            userId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
        if (status == null) {
            status = UserStatus.PENDING_KYC;
        }
        if (roles == null) {
            roles = List.of("USER");
        }
        if (failedLoginAttempts == 0) {
            failedLoginAttempts = 0;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    // ─── Domain behavior methods ───────────────────────────

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return status == UserStatus.SUSPENDED;
    }

    public boolean isAccountLocked() {
        return lockUntil != null && Instant.now().isBefore(lockUntil);
    }

    public boolean isKycVerified() {
        return kycVerifiedAt != null && status == UserStatus.ACTIVE;
    }

    public void incrementFailedLoginAttempts() {
        this.failedLoginAttempts++;
    }

    public void resetFailedLoginAttempts() {
        this.failedLoginAttempts = 0;
        this.lockUntil = null;
    }

    public void lockAccount(Instant until) {
        this.lockUntil = until;
        this.status = UserStatus.SUSPENDED;
    }

    public void approveKyc() {
        this.status = UserStatus.ACTIVE;
        this.kycVerifiedAt = Instant.now();
        this.kycNextReviewAt = Instant.now()
                .plus(java.time.Duration.ofDays(90));
    }

    public void rejectKyc() {
        this.status = UserStatus.KYC_REJECTED;
    }

    public void permanentlyRejectKyc() {
        this.status = UserStatus.KYC_REJECTED_PERMANENT;
    }

    public void softDelete() {
        this.deletedAt = Instant.now();
        this.status = UserStatus.CLOSED;
    }

    public void cancelRegistration() {
        this.deletedAt = Instant.now();
        this.status = UserStatus.REGISTRATION_CANCELLED;
    }
}