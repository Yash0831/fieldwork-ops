package com.fieldwork.ops.workorder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fieldwork.ops.auth.Role;
import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.RoleRepository;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.dispatch.DispatchService;
import jakarta.persistence.OptimisticLockException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Concurrency test for the dispatch assignment path against real
 * PostgreSQL (Testcontainers).
 *
 * <p>Eight threads race to assign the same ticket to eight different
 * technicians. The work-order row carries a JPA {@code @Version}
 * column, so the losers must fail with an optimistic-locking error —
 * never with a silent last-writer-wins.
 *
 * <p>Requires Docker. Excluded from the default unit-test run
 * (surefire); runs under {@code mvn verify} via failsafe.
 *
 * <p><strong>Not {@code @Transactional}:</strong> the fixtures must be
 * committed so the worker threads can see them; each worker runs in
 * its own transaction.
 */
@SpringBootTest
@Testcontainers
class ConcurrentAssignmentIntegrationTest {

    private static final int RACERS = 8;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderRepository workOrders;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private ExecutorService pool;

    private UUID ticketId;
    private List<UUID> technicianIds;
    private CurrentUser dispatcherActor;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(RACERS);
        Role requesterRole = roleRepository.findByName(RoleName.REQUESTER).orElseThrow();
        Role techRole = roleRepository.findByName(RoleName.TECHNICIAN).orElseThrow();
        Role dispatcherRole = roleRepository.findByName(RoleName.DISPATCHER).orElseThrow();

        User requester = saveUser("race-requester", requesterRole);
        User dispatcher = saveUser("race-dispatcher", dispatcherRole);
        dispatcherActor = new CurrentUser(dispatcher.getId(), dispatcher.getEmail(), RoleName.DISPATCHER);

        technicianIds = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            technicianIds.add(saveUser("race-tech-" + i, techRole).getId());
        }

        CreateWorkOrderCommand command = new CreateWorkOrderCommand(
                "Race ticket", "concurrency probe", WorkOrderPriority.P3, "GENERAL",
                requester.getId(), null, null);
        ticketId = workOrderService
                .create(command, "race-key-" + UUID.randomUUID(), "race-dispatcher@example.com", WorkOrder::getTicketNumber)
                .workOrder()
                .getId();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private User saveUser(String username, Role role) {
        User u = new User() {};
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setPasswordHash("{bcrypt}$2a$10$testhashforconcurrencyit000000000000000000");
        u.setFullName("Race " + username);
        u.setRole(role);
        u.setActive(true);
        return userRepository.save(u);
    }

    private static boolean causedByOptimisticLock(Throwable t) {
        while (t != null) {
            if (t instanceof OptimisticLockException
                    || t.getClass().getSimpleName().contains("OptimisticLock")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    @Test
    void concurrentAssignmentsRaceOnTheVersionColumn() throws Exception {
        CountDownLatch ready = new CountDownLatch(RACERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<WorkOrder>> futures = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            final UUID techId = technicianIds.get(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("start gate timed out");
                }
                return dispatchService.assign(techId, ticketId, dispatcherActor);
            }));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        int successes = 0;
        int optimisticFailures = 0;
        List<String> unexpected = new ArrayList<>();
        for (Future<WorkOrder> f : futures) {
            try {
                assertThat(f.get(10, TimeUnit.SECONDS).getId()).isEqualTo(ticketId);
                successes++;
            } catch (ExecutionException e) {
                if (causedByOptimisticLock(e)) {
                    optimisticFailures++;
                } else {
                    unexpected.add(e.getCause() != null ? e.getCause().toString() : e.toString());
                }
            }
        }

        assertThat(unexpected).as("no unexpected failure modes").isEmpty();
        assertThat(successes).as("at least one assignment wins the race").isGreaterThanOrEqualTo(1);
        // With 8 threads hammering a single versioned row, at least one
        // loser must observe the version conflict rather than silently
        // overwriting the winner.
        assertThat(optimisticFailures)
                .as("losers fail with optimistic locking, not silent last-writer-wins")
                .isGreaterThanOrEqualTo(1);

        // The ticket ends up assigned to exactly one technician.
        WorkOrder finalTicket = workOrders.findById(ticketId).orElseThrow();
        assertThat(finalTicket.getAssignee()).isNotNull();
        assertThat(finalTicket.getStatus()).isEqualTo(WorkOrderStatus.ASSIGNED);
    }
}
