package com.fieldwork.ops.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fieldwork.ops.auth.Role;
import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.RoleRepository;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.workorder.CreateWorkOrderCommand;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderPriority;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.WorkOrderService;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SLA breach-scanner test against real PostgreSQL (Testcontainers).
 *
 * <p>Drives {@link SlaBreachScanJob#scanForBreaches()} directly with
 * backdated tickets: a plain overdue ticket breaches, a ticket whose
 * effective age is covered by ON_HOLD pause time does not, a ticket
 * still overdue after subtracting the hold breaches its resolution
 * target, and re-running the scan never double-records.
 *
 * <p>Requires Docker. Excluded from the default unit-test run
 * (surefire); runs under {@code mvn verify} via failsafe.
 */
@SpringBootTest
@Testcontainers
@Transactional
class SlaBreachScanIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private SlaBreachScanJob scanJob;

    @Autowired
    private SlaService slaService;

    @Autowired
    private SlaPolicyRepository slaPolicyRepository;

    @Autowired
    private SlaBreachRepository breaches;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderRepository workOrders;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    private User requester;
    private User technician;
    private CurrentUser dispatcherActor;

    @BeforeEach
    void setUp() {
        Role requesterRole = roleRepository.findByName(RoleName.REQUESTER).orElseThrow();
        Role techRole = roleRepository.findByName(RoleName.TECHNICIAN).orElseThrow();
        Role dispatcherRole = roleRepository.findByName(RoleName.DISPATCHER).orElseThrow();
        requester = saveUser("sla-requester", requesterRole);
        technician = saveUser("sla-tech", techRole);
        User dispatcher = saveUser("sla-dispatcher", dispatcherRole);
        dispatcherActor = new CurrentUser(dispatcher.getId(), dispatcher.getEmail(), RoleName.DISPATCHER);

        // The V6 seed ships active base policies (incl. a P1 base row);
        // deactivate them so this test's explicit policy is the one that resolves.
        slaPolicyRepository.findAll().forEach(p -> p.setActive(false));
        // P1: 60-minute response target, 240-minute resolution target.
        slaService.createPolicy("P1 default", WorkOrderPriority.P1, null, 60, 240, true, "test");
    }

    private User saveUser(String username, Role role) {
        User u = new User() {};
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setPasswordHash("{bcrypt}$2a$10$testhashfortheslait0000000000000000000000");
        u.setFullName("SLA " + username);
        u.setRole(role);
        u.setActive(true);
        return userRepository.save(u);
    }

    private UUID createTicket(String keySuffix) {
        CreateWorkOrderCommand command = new CreateWorkOrderCommand(
                "SLA probe " + keySuffix, "scanner fixture", WorkOrderPriority.P1, "GENERAL",
                requester.getId(), null, null);
        return workOrderService
                .create(command, "sla-key-" + keySuffix, "sla-requester@example.com", WorkOrder::getTicketNumber)
                .workOrder()
                .getId();
    }

    /**
     * Backdates a ticket's creation (and optionally its ongoing hold)
     * with direct SQL, then clears the persistence context so the
     * scanner reads the aged rows.
     */
    private OffsetDateTime backdate(UUID ticketId, OffsetDateTime createdAt, OffsetDateTime onHoldSince) {
        if (onHoldSince == null) {
            jdbcTemplate.update("UPDATE work_orders SET created_at = ? WHERE id = ?", createdAt, ticketId);
        } else {
            jdbcTemplate.update(
                    "UPDATE work_orders SET created_at = ?, on_hold_since = ? WHERE id = ?",
                    createdAt,
                    onHoldSince,
                    ticketId);
        }
        entityManager.clear();
        return createdAt;
    }

    private UUID onHoldTicket(String keySuffix) {
        UUID id = createTicket(keySuffix);
        workOrderService.assignTicket(id, technician.getId(), dispatcherActor);
        workOrderService.transitionStatus(id, WorkOrderStatus.IN_PROGRESS, dispatcherActor, "started");
        workOrderService.transitionStatus(id, WorkOrderStatus.ON_HOLD, dispatcherActor, "waiting on parts");
        return id;
    }

    private long breachCount(UUID ticketId) {
        return breaches.findByWorkOrderId(ticketId).size();
    }

    @Test
    void scannerRecordsBreachesHonoringOnHoldPauseTime() {
        OffsetDateTime now = OffsetDateTime.now();

        // T1: OPEN for 2h, never responded -> RESPONSE breach (60m target).
        UUID overdue = createTicket("t1");
        OffsetDateTime t1Created = backdate(overdue, now.minusHours(2), null);

        // T2: 3h old but on hold for the last 2.5h -> effective age 30m -> no breach.
        UUID paused = onHoldTicket("t2");
        backdate(paused, now.minusHours(3), now.minusMinutes(150));

        // T3: 5h old, on hold for the last 50m -> effective age ~4h10m ->
        // RESOLUTION breach (240m target); response gate already satisfied.
        UUID longOverdue = onHoldTicket("t3");
        backdate(longOverdue, now.minusHours(5), now.minusMinutes(50));

        scanJob.scanForBreaches();

        // --- T1: response breach recorded, resolution untouched ---
        assertThat(breaches.findByWorkOrderIdAndBreachType(overdue, BreachType.RESPONSE))
                .hasValueSatisfying(b -> {
                    assertThat(b.getDetectedAt()).isNotNull();
                    // breachedAt estimates the crossing: created + 60m response target.
                    assertThat(b.getBreachedAt())
                            .isCloseTo(t1Created.plusMinutes(60), within(5, ChronoUnit.MINUTES));
                });
        assertThat(breaches.findByWorkOrderIdAndBreachType(overdue, BreachType.RESOLUTION)).isEmpty();

        // --- T2: ON_HOLD pause covers the age -> no breach at all ---
        assertThat(breachCount(paused)).isZero();

        // --- T3: resolution breach despite the hold ---
        assertThat(breachCount(longOverdue)).isEqualTo(1);
        assertThat(breaches.findByWorkOrderIdAndBreachType(longOverdue, BreachType.RESOLUTION)).isPresent();
        assertThat(breaches.findByWorkOrderIdAndBreachType(longOverdue, BreachType.RESPONSE)).isEmpty();

        // --- re-running the scan is idempotent: no duplicate rows ---
        scanJob.scanForBreaches();
        assertThat(breachCount(overdue)).isEqualTo(1);
        assertThat(breachCount(paused)).isZero();
        assertThat(breachCount(longOverdue)).isEqualTo(1);
    }

    @Test
    void scannerIgnoresTerminalTickets() {
        UUID id = createTicket("terminal");
        workOrderService.assignTicket(id, technician.getId(), dispatcherActor);
        workOrderService.transitionStatus(id, WorkOrderStatus.IN_PROGRESS, dispatcherActor, "started");
        workOrderService.transitionStatus(id, WorkOrderStatus.RESOLVED, dispatcherActor, "fixed");
        workOrderService.transitionStatus(id, WorkOrderStatus.CLOSED, dispatcherActor, "verified");
        backdate(id, OffsetDateTime.now().minusHours(10), null);

        scanJob.scanForBreaches();

        assertThat(breachCount(id)).isZero();
        assertThat(workOrders.findById(id).orElseThrow().getStatus()).isEqualTo(WorkOrderStatus.CLOSED);
    }
}
