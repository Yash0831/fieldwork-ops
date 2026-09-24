package com.fieldwork.ops.workorder;

import com.fieldwork.ops.auth.Team;
import com.fieldwork.ops.auth.User;
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
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The central aggregate of the system. Key mapping notes:
 * <ul>
 *   <li>{@code ticketNumber} is the human-facing identifier
 *   ('WO-001000'), allocated from the {@code ticket_number_seq}
 *   database sequence — it is assigned once at creation and never
 *   changes.</li>
 *   <li>{@code version} is the JPA optimistic-locking column; the
 *   guarded transitions and dispatch assignment all race on this row.</li>
 *   <li>Relationships are unidirectional many-to-one on purpose:
 *   traversal starts at the work order, never the reverse, which keeps
 *   the aggregate boundary explicit and avoids lazy-loading surprises.</li>
 * </ul>
 */
@Entity
@Table(name = "work_orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ticket_number", nullable = false, unique = true, length = 16)
    private String ticketNumber;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private WorkOrderStatus status = WorkOrderStatus.OPEN;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 8)
    private WorkOrderPriority priority = WorkOrderPriority.P3;

    @Column(name = "category", nullable = false, length = 64)
    private String category = "GENERAL";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /** First-response deadline from the matched SLA policy. */
    @Column(name = "response_due_at")
    private OffsetDateTime responseDueAt;

    /** Resolution deadline from the matched SLA policy. */
    @Column(name = "resolution_due_at")
    private OffsetDateTime resolutionDueAt;

    /** Headline deadline used for queue ordering (usually the resolution target). */
    @Column(name = "due_at")
    private OffsetDateTime dueAt;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Column(name = "estimated_hours", precision = 6, scale = 2)
    private BigDecimal estimatedHours;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

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
