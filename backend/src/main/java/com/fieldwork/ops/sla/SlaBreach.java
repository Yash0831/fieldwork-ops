package com.fieldwork.ops.sla;

import com.fieldwork.ops.workorder.WorkOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The immutable breach record. Insert-only by convention: the breach
 * scanner creates one row per (work order, breach type) — enforced by
 * the database unique constraint — and only {@code resolvedAt} is
 * ever updated, when the underlying work order reaches a terminal
 * state. This record is the defensible evidence in contract disputes.
 */
@Entity
@Table(name = "sla_breaches")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlaBreach {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = false)
    private WorkOrder workOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_id", nullable = false)
    private SlaPolicy policy;

    @Enumerated(EnumType.STRING)
    @Column(name = "breach_type", nullable = false, length = 16)
    private BreachType breachType;

    /** When the deadline passed. */
    @Column(name = "breached_at", nullable = false)
    private OffsetDateTime breachedAt;

    /** When the scanner recorded the breach (may lag breachedAt). Set by
     * the scanner; defaults to NOW() at the database level. */
    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_by")
    private String updatedBy;
}
