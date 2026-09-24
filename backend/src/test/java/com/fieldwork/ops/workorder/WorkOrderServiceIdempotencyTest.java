package com.fieldwork.ops.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fieldwork.ops.auth.TeamRepository;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.IdempotencyConflictException;
import com.fieldwork.ops.sla.SlaService;
import com.fieldwork.ops.workorder.event.WorkOrderCreatedEvent;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for idempotent ticket creation.
 *
 * <p>All persistence is mocked; the tests drive the real claim/replay
 * logic in {@link WorkOrderService#create}, including the concurrent
 * claim race that is resolved through the primary-key constraint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkOrderServiceIdempotencyTest {

    private static final Instant T0 = Instant.parse("2026-09-23T12:00:00Z");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final Clock CLOCK = Clock.fixed(T0, UTC);
    private static final String KEY = "idem-key-001";
    private static final String TICKET_NUMBER = "WO-2026-000042";

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

    private final WorkOrderResponseSerializer serializer =
            wo -> "{\"ticketNumber\":\"" + wo.getTicketNumber() + "\"}";

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

    private static CreateWorkOrderCommand command(UUID requesterId) {
        return new CreateWorkOrderCommand(
                "Fix leaking pipe", "Basement unit 3", WorkOrderPriority.P2, "PLUMBING", requesterId, null, null);
    }

    private static User user(UUID id, String username) {
        // User's constructor is protected and lives in another package: subclass to instantiate.
        User u = new User() {};
        u.setId(id);
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        return u;
    }

    /** Stubs the intake path and runs one full successful creation, returning the captured claim. */
    private IdempotencyKey runFirstCreate(String key, CreateWorkOrderCommand cmd, UUID requesterId) {
        when(idempotencyKeys.findById(key)).thenReturn(Optional.empty());
        when(users.findById(requesterId)).thenReturn(Optional.of(user(requesterId, "requester")));
        when(ticketNumbers.generate()).thenReturn(TICKET_NUMBER);
        when(workOrders.save(any(WorkOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderCreation creation = service.create(cmd, key, "requester@example.com", serializer);
        assertThat(creation.replayed()).isFalse();

        ArgumentCaptor<IdempotencyKey> captor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyKeys, times(1)).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------
    // Fresh creation
    // ------------------------------------------------------------------

    @Test
    void createWithoutKeySkipsIdempotencyEntirely() {
        UUID requesterId = UUID.randomUUID();
        CreateWorkOrderCommand cmd = command(requesterId);
        when(users.findById(requesterId)).thenReturn(Optional.of(user(requesterId, "requester")));
        when(ticketNumbers.generate()).thenReturn(TICKET_NUMBER);
        when(workOrders.save(any(WorkOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderCreation creation = service.create(cmd, null, "requester@example.com", serializer);

        assertThat(creation.replayed()).isFalse();
        assertThat(creation.responseStatus()).isEqualTo(201);
        assertThat(creation.responseBody()).isEqualTo("{\"ticketNumber\":\"" + TICKET_NUMBER + "\"}");
        assertThat(creation.workOrder().getTicketNumber()).isEqualTo(TICKET_NUMBER);
        assertThat(creation.workOrder().getStatus()).isEqualTo(WorkOrderStatus.OPEN);
        verify(idempotencyKeys, never()).saveAndFlush(any());
        verify(idempotencyKeys, never()).findById(any());
        verify(slaService).applyDeadlines(any(WorkOrder.class));
        verify(history).save(any(WorkOrderStatusHistory.class));
        verify(events).publishEvent(any(WorkOrderCreatedEvent.class));
    }

    @Test
    void createWithKeyCompletesTheClaim() {
        UUID requesterId = UUID.randomUUID();
        CreateWorkOrderCommand cmd = command(requesterId);

        IdempotencyKey claim = runFirstCreate(KEY, cmd, requesterId);

        assertThat(claim.getIdemKey()).isEqualTo(KEY);
        assertThat(claim.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(claim.getResponseStatus()).isEqualTo(201);
        assertThat(claim.getResponseBody()).isEqualTo("{\"ticketNumber\":\"" + TICKET_NUMBER + "\"}");
        assertThat(claim.getWorkOrder()).isNotNull();
        assertThat(claim.getWorkOrder().getTicketNumber()).isEqualTo(TICKET_NUMBER);
        // Keys live for 24h from the injected clock.
        assertThat(claim.getExpiresAt()).isEqualTo(OffsetDateTime.ofInstant(T0, UTC).plusHours(24));
    }

    // ------------------------------------------------------------------
    // Replay
    // ------------------------------------------------------------------

    @Test
    void duplicateCreateWithSameKeyReturnsTheOriginalTicket() {
        UUID requesterId = UUID.randomUUID();
        CreateWorkOrderCommand cmd = command(requesterId);
        IdempotencyKey claim = runFirstCreate(KEY, cmd, requesterId);
        WorkOrder original = claim.getWorkOrder();
        when(idempotencyKeys.findById(KEY)).thenReturn(Optional.of(claim));

        WorkOrderCreation replayed = service.create(cmd, KEY, "requester@example.com", serializer);

        assertThat(replayed.replayed()).isTrue();
        assertThat(replayed.workOrder()).isSameAs(original);
        assertThat(replayed.responseBody()).isEqualTo(claim.getResponseBody());
        assertThat(replayed.responseStatus()).isEqualTo(201);
        // No second ticket was ever persisted.
        verify(workOrders, times(1)).save(any(WorkOrder.class));
        verify(ticketNumbers, times(1)).generate();
        verify(idempotencyKeys, times(1)).saveAndFlush(any());
    }

    @Test
    void sameKeyWithDifferentPayloadIsAConflict() {
        UUID requesterId = UUID.randomUUID();
        IdempotencyKey claim = runFirstCreate(KEY, command(requesterId), requesterId);
        when(idempotencyKeys.findById(KEY)).thenReturn(Optional.of(claim));

        CreateWorkOrderCommand different = new CreateWorkOrderCommand(
                "Different title", "Basement unit 3", WorkOrderPriority.P2, "PLUMBING", requesterId, null, null);

        assertThatThrownBy(() -> service.create(different, KEY, "requester@example.com", serializer))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different payload");
        verify(workOrders, times(1)).save(any(WorkOrder.class));
    }

    @Test
    void keyWithInFlightRequestIsAConflict() {
        UUID requesterId = UUID.randomUUID();
        CreateWorkOrderCommand cmd = command(requesterId);
        IdempotencyKey inFlight = new IdempotencyKey();
        inFlight.setIdemKey(KEY);
        inFlight.setRequester("requester@example.com");
        inFlight.setRequestHash("hash");
        inFlight.setStatus(IdempotencyStatus.IN_PROGRESS);
        inFlight.setExpiresAt(OffsetDateTime.ofInstant(T0, UTC).plusHours(1));
        when(idempotencyKeys.findById(KEY)).thenReturn(Optional.of(inFlight));

        assertThatThrownBy(() -> service.create(cmd, KEY, "requester@example.com", serializer))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("still in progress");
        verify(workOrders, never()).save(any(WorkOrder.class));
    }

    @Test
    void expiredInFlightKeyIsReclaimedAndTreatedAsNew() {
        UUID requesterId = UUID.randomUUID();
        CreateWorkOrderCommand cmd = command(requesterId);
        IdempotencyKey stale = new IdempotencyKey();
        stale.setIdemKey(KEY);
        stale.setRequester("requester@example.com");
        stale.setRequestHash("hash");
        stale.setStatus(IdempotencyStatus.IN_PROGRESS);
        stale.setExpiresAt(OffsetDateTime.ofInstant(T0, UTC).minusSeconds(1));
        when(idempotencyKeys.findById(KEY)).thenReturn(Optional.of(stale));
        when(users.findById(requesterId)).thenReturn(Optional.of(user(requesterId, "requester")));
        when(ticketNumbers.generate()).thenReturn(TICKET_NUMBER);
        when(workOrders.save(any(WorkOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderCreation creation = service.create(cmd, KEY, "requester@example.com", serializer);

        assertThat(creation.replayed()).isFalse();
        assertThat(creation.workOrder().getTicketNumber()).isEqualTo(TICKET_NUMBER);
        verify(idempotencyKeys).delete(stale);
        verify(entityManager).flush();
    }

    @Test
    void concurrentClaimRaceReplaysTheWinnersTicket() {
        UUID requesterId = UUID.randomUUID();
        CreateWorkOrderCommand cmd = command(requesterId);
        // The "winner" completed first; its claim row is what the loser will find.
        IdempotencyKey winnerClaim = runFirstCreate(KEY, cmd, requesterId);
        WorkOrder winnersTicket = winnerClaim.getWorkOrder();

        // Fresh attempt: no row visible yet, but the claim insert loses the PK race.
        when(idempotencyKeys.findById(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerClaim));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(idempotencyKeys)
                .saveAndFlush(any(IdempotencyKey.class));

        WorkOrderCreation creation = service.create(cmd, KEY, "requester@example.com", serializer);

        assertThat(creation.replayed()).isTrue();
        assertThat(creation.workOrder()).isSameAs(winnersTicket);
        // The poisoned persistence context is cleared before replaying.
        verify(entityManager).clear();
        // The loser never created its own ticket.
        verify(workOrders, times(1)).save(any(WorkOrder.class));
    }

    @Test
    void blankKeyIsTreatedAsNoKey() {
        UUID requesterId = UUID.randomUUID();
        CreateWorkOrderCommand cmd = command(requesterId);
        when(users.findById(requesterId)).thenReturn(Optional.of(user(requesterId, "requester")));
        when(ticketNumbers.generate()).thenReturn(TICKET_NUMBER);
        when(workOrders.save(any(WorkOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkOrderCreation creation = service.create(cmd, "   ", "requester@example.com", serializer);

        assertThat(creation.replayed()).isFalse();
        verify(idempotencyKeys, never()).findById(any());
        verify(idempotencyKeys, never()).saveAndFlush(any());
    }
}
