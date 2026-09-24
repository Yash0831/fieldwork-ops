package com.fieldwork.ops.sla;

import com.fieldwork.ops.workorder.WorkOrderPriority;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * SLA policy: the response and resolution targets (in minutes) for a
 * priority, optionally narrowed to one category. A {@code null}
 * category applies to all categories; the partial unique index in V4
 * guarantees a single active policy per (priority, category).
 */
@Entity
@Table(name = "sla_policies")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 8)
    private WorkOrderPriority priority;

    @Column(name = "category", length = 64)
    private String category;

    /** Minutes allowed before first response. */
    @Column(name = "response_minutes", nullable = false)
    private int responseMinutes;

    /** Minutes allowed before resolution. */
    @Column(name = "resolution_minutes", nullable = false)
    private int resolutionMinutes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
