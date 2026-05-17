package com.nexus.identity.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * AuditLog — Immutable security event record.
 *
 * NEVER updated or deleted — a PostgreSQL trigger enforces this.
 * See V4__create_audit_log.sql: trigger_audit_log_immutable.
 *
 * Retained for 7+ years per CNBV financial compliance requirements.
 * Linked to Zipkin traces via traceId for correlation.
 *
 * userId is nullable: events before registration (e.g., failed login
 * for unknown email) still get an audit record.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@EqualsAndHashCode(of = "auditId")
public class AuditLog {

    @Id
    @Column(name = "audit_id", updatable = false)
    private UUID auditId;

    /**
     * Nullable — pre-registration events don't have a userId yet.
     * No FK on this column: audit records survive user soft-delete.
     */
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "user_agent", columnDefinition = "TEXT")
    private String userAgent;

    /**
     * Event-specific structured data (JSONB).
     * Examples:
     *   LOGIN_FAILED: {"email": "t***@x.com", "reason": "INVALID_CREDENTIALS"}
     *   KYC_APPROVED: {"verificationId": "...", "documentType": "PASSPORT"}
     */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode details;

    @Column(name = "occurred_at", updatable = false)
    private Instant occurredAt;

    /** Zipkin traceId — correlate with distributed traces in Zipkin/Grafana */
    @Column(name = "trace_id", length = 32)
    private String traceId;

    @PrePersist
    void prePersist() {
        if (auditId == null) auditId = UUID.randomUUID();
        if (occurredAt == null) occurredAt = Instant.now();
    }
}