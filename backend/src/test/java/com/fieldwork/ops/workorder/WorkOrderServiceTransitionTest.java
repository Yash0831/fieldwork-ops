package com.fieldwork.ops.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.TeamRepository;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.IllegalStateTransitionException;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.sla.SlaService;
import com.fieldwork.ops.workorder.event.WorkOrderStatusChangedEvent;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for assignment, guarded transitions, and the
 * service-layer ownership checks in {@link WorkOrderService}.
 */
@ExtendWith(MockitoExtension.class)
class WorkOrderServiceTransitionTest {

    private static final Instant T0 = Instant.parse("2026-09-23T12:00:00Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Clock CLOCK = Clock.fixed(T0, UTC);

    @Mock
    private WorkOrderRepository workOrders;

    @Mock
    private WorkOrderStatusHistoryRepository history;

    @Mock
    private IdempotencyKeyRepository idempotencyKeys;

    @Mock
    private CommentRepository comments;

    @Mock
    private UserRepository users;

    @Mock
    private TeamRepository teams;

    @Mock
    private SlaService slaService;

    @Mock
    private TicketNumberGenerator ticketNumbers;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private EntityManager entityManager;

    @Mock
    private WorkOrderMapper mapper;

    private WorkOrderService service;

    @BeforeEach
    void setUp() {
        service = new WorkOrderService(
                workOrders,
                history,
                idempotencyKeys,
                comments,
                users,
                teams,
                slaService,
                ticketNumbers,
                events,
                CLOCK,
                entityManager,
                mapper);
    }

    private static User user(UUID id, String username) {
        // User's constructor is protected and lives in another package: subclass to instantiate.
        User u = new User() {};
        u.setId(id);
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        return u;
    }

    private static WorkOrder ticket(UUID id, WorkOrderStatus status) {
        WorkOrder wo = new WorkOrder();
        wo.setId(id);
        wo.setTicketNumber("WO-2026-000001");
        wo.setStatus(status);
        return wo;
    }

    private static CurrentUser actor(UUID id, RoleName role) {
        return new CurrentUser(id, "actor@example.com", role);
    }

    // ------------------------------------------------------------------
    // assignTicket
    // ------------------------------------------------------------------

    @Test
    void assignTicketMovesOpenToAssigned() {
        UUID ticketId = UUID.randomUUID();
        UUID techId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WorkOrder wo = ticket(ticketId, WorkOrderStatus.OPEN);
        User tech = user(techId, "tech1");
        User dispatcher = user(actorId, "dispatcher");
        when(workOrders.findById(ticketId)).thenReturn(Optional.of(wo));
        when(users.findById(techId)).thenReturn(Optional.of(tech));
        when(users.findById(actorId)).thenReturn(Optional.of(dispatcher));

        WorkOrder result = service.assignTicket(ticketId, techId, actor(actorId, RoleName.DISPATCHER));

        assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
        assertThat(result.getAssignee()).isSameAs(tech);
        // First movement out of OPEN counts as the SLA response.
        assertThat(result.getRespondedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, UTC));
        assertThat(result.getUpdatedBy()).isEqualTo("dispatcher");

        ArgumentCaptor<WorkOrderStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(WorkOrderStatusHistory.class);
        verify(history).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getFromStatus()).isEqualTo(WorkOrderStatus.OPEN);
        assertThat(historyCaptor.getValue().getToStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);

        ArgumentCaptor<WorkOrderStatusChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(WorkOrderStatusChangedEvent.class);
        verify(events).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().fromStatus()).isEqualTo(WorkOrderStatus.OPEN);
        assertThat(eventCaptor.getValue().toStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
    }

    @Test
    void reassigningAnAssignedTicketIsNotAStateMachineTransition() {
        UUID ticketId = UUID.randomUUID();
        UUID oldTechId = UUID.randomUUID();
        UUID newTechId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WorkOrder wo = ticket(ticketId, WorkOrderStatus.ASSIGNED);
        wo.setAssignee(user(oldTechId, "old-tech"));
        User newTech = user(newTechId, "new-tech");
        User dispatcher = user(actorId, "dispatcher");
        when(workOrders.findById(ticketId)).thenReturn(Optional.of(wo));
        when(users.findById(newTechId)).thenReturn(Optional.of(newTech));
        when(users.findById(actorId)).thenReturn(Optional.of(dispatcher));

        WorkOrder result = service.assignTicket(ticketId, newTechId, actor(actorId, RoleName.DISPATCHER));

        // The assignee changes, the status does not, and no status-changed event fires.
        assertThat(result.getAssignee()).isSameAs(newTech);
        assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
        assertThat(result.getRespondedAt()).isNull();

        ArgumentCaptor<WorkOrderStatusHistory> historyCaptor =
                ArgumentCaptor.forClass(WorkOrderStatusHistory.class);
        verify(history).save(historyCaptor.capture());
        WorkOrderStatusHistory entry = historyCaptor.getValue();
        assertThat(entry.getFromStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
        assertThat(entry.getToStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
        assertThat(entry.getNote()).contains("new-tech");

        verify(events, never()).publishEvent(any(WorkOrderStatusChangedEvent.class));
    }

    @Test
    void assignTicketOnUnknownTicketIsNotFound() {
        UUID ticketId = UUID.randomUUID();
        when(workOrders.findById(ticketId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                        service.assignTicket(ticketId, UUID.randomUUID(), actor(UUID.randomUUID(), RoleName.DISPATCHER)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // transitionStatus guards
    // ------------------------------------------------------------------

    @Test
    void illegalTransitionIsRejectedBeforeAnySideEffect() {
        UUID ticketId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        WorkOrder wo = ticket(ticketId, WorkOrderStatus.OPEN);
        when(workOrders.findById(ticketId)).thenReturn(Optional.of(wo));
        when(users.findById(actorId)).thenReturn(Optional.of(user(actorId, "admin")));

        assertThatThrownBy(() -> service.transitionStatus(
                        ticketId, WorkOrderStatus.RESOLVED, actor(actorId, RoleName.ADMIN), "skip ahead"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.OPEN);
        verify(history, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void legalTransitionStampsLifecycleTimestamps() {
        UUID ticketId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        User tech = user(actorId, "tech1");
        WorkOrder wo = ticket(ticketId, WorkOrderStatus.IN_PROGRESS);
        wo.setAssignee(tech); // ownership: the technician holds this ticket
        when(workOrders.findById(ticketId)).thenReturn(Optional.of(wo));
        when(users.findById(actorId)).thenReturn(Optional.of(tech));

        WorkOrder result =
                service.transitionStatus(ticketId, WorkOrderStatus.RESOLVED, actor(actorId, RoleName.TECHNICIAN), "done");

        assertThat(result.getStatus()).isEqualTo(WorkOrderStatus.RESOLVED);
        assertThat(result.getResolvedAt()).isEqualTo(OffsetDateTime.ofInstant(T0, UTC));
        verify(events).publishEvent(any(WorkOrderStatusChangedEvent.class));
    }

    // ------------------------------------------------------------------
    // checkTicketAccess: the service-layer ownership rule
    // ------------------------------------------------------------------

    private WorkOrder ownedTicket() {
        UUID requesterId = UUID.randomUUID();
        UUID techId = UUID.randomUUID();
        WorkOrder wo = new WorkOrder();
        wo.setTicketNumber("WO-2026-000002");
        wo.setRequester(user(requesterId, "requester"));
        wo.setAssignee(user(techId, "tech1"));
        return wo;
    }

    @Test
    void adminAndDispatcherMayAccessAnyTicket() {
        WorkOrder wo = ownedTicket();
        service.checkTicketAccess(wo, actor(UUID.randomUUID(), RoleName.ADMIN));
        service.checkTicketAccess(wo, actor(UUID.randomUUID(), RoleName.DISPATCHER));
    }

    @Test
    void technicianMayAccessOnlyAssignedTickets() {
        WorkOrder wo = ownedTicket();
        UUID assignedTech = wo.getAssignee().getId();

        service.checkTicketAccess(wo, actor(assignedTech, RoleName.TECHNICIAN));

        assertThatThrownBy(() -> service.checkTicketAccess(wo, actor(UUID.randomUUID(), RoleName.TECHNICIAN)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("not assigned to you");

        wo.setAssignee(null);
        assertThatThrownBy(() -> service.checkTicketAccess(wo, actor(assignedTech, RoleName.TECHNICIAN)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requesterMayAccessOnlyOwnTickets() {
        WorkOrder wo = ownedTicket();
        UUID requester = wo.getRequester().getId();

        service.checkTicketAccess(wo, actor(requester, RoleName.REQUESTER));

        assertThatThrownBy(() -> service.checkTicketAccess(wo, actor(UUID.randomUUID(), RoleName.REQUESTER)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("only access tickets you requested");
    }
}
