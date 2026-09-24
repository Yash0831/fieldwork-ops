package com.fieldwork.ops.common.audit;

import com.fieldwork.ops.auth.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit record: who did what, to which entity, when.
 * Lives in {@code common} because every module writes to it.
 * {@code details} carries a JSONB payload (typically a before/after
 * diff); the row is never updated or deleted by application code.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    /** Denormalised actor name so the record survives user deletion. */
    @Column(name = "actor_name", length = 128)
    private String actorName;

    /** e.g. WORK_ORDER_CREATED, STATUS_CHANGED, SLA_BREACHED. */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** Simple entity name, e.g. WorkOrder. */
    @Column(name = "entity_type", nullable = false, length = 64)
    private String entityType;

    /** Stringified entity id (UUIDs today, agnostic tomorrow). */
    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private Map<String, Object> details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
