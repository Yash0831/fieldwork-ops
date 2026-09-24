package com.fieldwork.ops.workorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fieldwork.ops.auth.Role;
import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.RoleRepository;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.IllegalStateTransitionException;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.sla.SlaPolicyRepository;
import com.fieldwork.ops.sla.SlaService;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end lifecycle test against real PostgreSQL (Testcontainers).
 *
 * <p>Covers the full ticket journey — create, idempotent re-create,
 * assign, guarded transitions, ON_HOLD pause stamping, history rows —
 * plus the terminal-state freeze, all through the real service beans
 * and Flyway-migrated schema.
 *
 * <p>Requires Docker. Excluded from the default unit-test run
 * (surefire); runs under {@code mvn verify} via failsafe.
 */
@SpringBootTest
@Testcontainers
@Transactional
class WorkOrderLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderRepository workOrders;

    @Autowired
    private WorkOrderStatusHistoryRepository history;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private SlaService slaService;

    @Autowired
    private SlaPolicyRepository slaPolicyRepository;

    private User requester;
    private User technician;
    private CurrentUser dispatcherActor;

    private final WorkOrderResponseSerializer serializer = WorkOrder::getTicketNumber;

    @BeforeEach
    void setUp() {
        Role requesterRole = roleRepository.findByName(RoleName.REQUESTER).orElseThrow();
        Role techRole = roleRepository.findByName(RoleName.TECHNICIAN).orElseThrow();
        Role dispatcherRole = roleRepository.findByName(RoleName.DISPATCHER).orElseThrow();
        requester = saveUser("requester-it", requesterRole);
        technician = saveUser("tech-it", techRole);
        User dispatcher = saveUser("dispatcher-it", dispatcherRole);
        dispatcherActor = new CurrentUser(dispatcher.getId(), dispatcher.getEmail(), RoleName.DISPATCHER);

        // The V6 seed ships active base policies; deactivate them so this
        // test's explicit policy is the one that resolves.
        slaPolicyRepository.findAll().forEach(p -> p.setActive(false));
        // P2 policy so SLA deadlines are stamped at creation.
        slaService.createPolicy("P2 default", WorkOrderPriority.P2, null, 60, 480, true, "test");
    }

    private User saveUser(String username, Role role) {
        // User's constructor is protected and lives in another package: subclass to instantiate.
        User u = new User() {};
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setPasswordHash("{bcrypt}$2a$10$testhashforthelifecycleit000000000000000000");
        u.setFullName("IT " + username);
        u.setRole(role);
        u.setActive(true);
        return userRepository.save(u);
    }

    private CreateWorkOrderCommand command() {
        return new CreateWorkOrderCommand(
                "Replace lobby light", "Flickering panel", WorkOrderPriority.P2, "ELECTRICAL",
                requester.getId(), null, null);
    }

    // ------------------------------------------------------------------
    // Full lifecycle
    // ------------------------------------------------------------------

    @Test
    void fullLifecycleFromCreateToClose() {
        WorkOrderCreation creation =
                workOrderService.create(command(), "lifecycle-key-1", "requester-it@example.com", serializer);
        UUID ticketId = creation.workOrder().getId();

        // --- creation ---
        assertThat(creation.replayed()).isFalse();
        assertThat(creation.workOrder().getTicketNumber()).matches("WO-\\d{4}-\\d{6}");
        assertThat(creation.workOrder().getStatus()).isEqualTo(WorkOrderStatus.OPEN);
        assertThat(creation.workOrder().getResponseDueAt())
                .isCloseTo(OffsetDateTime.now().plusMinutes(60), within(5, ChronoUnit.MINUTES));
        assertThat(creation.workOrder().getResolutionDueAt())
                .isCloseTo(OffsetDateTime.now().plusMinutes(480), within(5, ChronoUnit.MINUTES));
        assertThat(history.findByWorkOrderIdWithChanger(ticketId)).hasSize(1);

        // --- assignment: OPEN -> ASSIGNED, first response stamped ---
        WorkOrder assigned = workOrderService.assignTicket(ticketId, technician.getId(), dispatcherActor);
        assertThat(assigned.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
        assertThat(assigned.getAssignee().getId()).isEqualTo(technician.getId());
        assertThat(assigned.getRespondedAt()).isNotNull();

        // --- guarded transitions with ON_HOLD pause stamping ---
        workOrderService.transitionStatus(ticketId, WorkOrderStatus.IN_PROGRESS, dispatcherActor, "started");
        WorkOrder onHold =
                workOrderService.transitionStatus(ticketId, WorkOrderStatus.ON_HOLD, dispatcherActor, "waiting");
        assertThat(onHold.getStatus()).isEqualTo(WorkOrderStatus.ON_HOLD);
        assertThat(onHold.getOnHoldSince()).isNotNull();

        WorkOrder resumed =
                workOrderService.transitionStatus(ticketId, WorkOrderStatus.IN_PROGRESS, dispatcherActor, "resumed");
        assertThat(resumed.getOnHoldSince()).isNull();

        WorkOrder resolved =
                workOrderService.transitionStatus(ticketId, WorkOrderStatus.RESOLVED, dispatcherActor, "fixed");
        assertThat(resolved.getResolvedAt()).isNotNull();

        WorkOrder closed =
                workOrderService.transitionStatus(ticketId, WorkOrderStatus.CLOSED, dispatcherActor, "verified");
        assertThat(closed.getClosedAt()).isNotNull();

        // create + assign + 5 transitions = 7 history rows.
        List<WorkOrderStatusHistory> entries = history.findByWorkOrderIdWithChanger(ticketId);
        assertThat(entries).hasSize(7);
        assertThat(entries.get(entries.size() - 1).getToStatus()).isEqualTo(WorkOrderStatus.CLOSED);

        // --- terminal state: frozen ---
        assertThatThrownBy(() -> workOrderService.transitionStatus(
                        ticketId, WorkOrderStatus.CANCELLED, dispatcherActor, "too late"))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThat(workOrders.findById(ticketId).orElseThrow().getStatus()).isEqualTo(WorkOrderStatus.CLOSED);
    }

    // ------------------------------------------------------------------
    // Idempotent creation against the real database
    // ------------------------------------------------------------------

    @Test
    void duplicateCreateWithSameKeyReturnsTheSameTicket() {
        String key = "lifecycle-key-2";

        WorkOrderCreation first = workOrderService.create(command(), key, "requester-it@example.com", serializer);
        WorkOrderCreation second = workOrderService.create(command(), key, "requester-it@example.com", serializer);

        assertThat(second.replayed()).isTrue();
        assertThat(second.workOrder().getId()).isEqualTo(first.workOrder().getId());
        assertThat(second.responseBody()).isEqualTo(first.responseBody());
        assertThat(second.responseStatus()).isEqualTo(201);

        // Exactly one ticket row exists for the number, and the key row is completed.
        String ticketNumber = first.workOrder().getTicketNumber();
        assertThat(workOrders.findByTicketNumber(ticketNumber)).isPresent();
        assertThat(idempotencyKeys.findById(key))
                .hasValueSatisfying(k -> assertThat(k.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED));
    }

    @Test
    void illegalSkipTransitionIsRejectedEndToEnd() {
        WorkOrderCreation creation =
                workOrderService.create(command(), "lifecycle-key-3", "requester-it@example.com", serializer);
        UUID ticketId = creation.workOrder().getId();

        assertThatThrownBy(() -> workOrderService.transitionStatus(
                        ticketId, WorkOrderStatus.RESOLVED, dispatcherActor, "skip"))
                .isInstanceOf(IllegalStateTransitionException.class);

        assertThat(workOrders.findById(ticketId).orElseThrow().getStatus()).isEqualTo(WorkOrderStatus.OPEN);
        assertThat(history.findByWorkOrderIdWithChanger(ticketId)).hasSize(1);
    }
}
