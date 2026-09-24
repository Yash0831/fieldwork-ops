package com.fieldwork.ops.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fieldwork.ops.auth.Role;
import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.auth.UserRepository;
import com.fieldwork.ops.common.exception.ResourceNotFoundException;
import com.fieldwork.ops.common.exception.UserNotAssignableException;
import com.fieldwork.ops.common.exception.WorkloadLimitExceededException;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.dispatch.dto.TechnicianResponse;
import com.fieldwork.ops.workorder.TechnicianLoad;
import com.fieldwork.ops.workorder.WorkOrder;
import com.fieldwork.ops.workorder.WorkOrderMapper;
import com.fieldwork.ops.workorder.WorkOrderRepository;
import com.fieldwork.ops.workorder.WorkOrderService;
import com.fieldwork.ops.workorder.WorkOrderStatus;
import com.fieldwork.ops.workorder.dto.WorkOrderResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the dispatch workload rule.
 *
 * <p>A technician may hold at most
 * {@code dispatch.max-open-tickets-per-technician} OPEN/IN_PROGRESS
 * tickets; assignment at or above the cap is rejected before the
 * work-order module is ever touched.
 */
@ExtendWith(MockitoExtension.class)
class DispatchServiceTest {

    private static final List<WorkOrderStatus> ACTIVE_STATES =
            List.of(WorkOrderStatus.OPEN, WorkOrderStatus.IN_PROGRESS);

    @Mock
    private WorkOrderRepository workOrders;

    @Mock
    private UserRepository users;

    @Mock
    private WorkOrderService workOrderService;

    @Mock
    private WorkOrderMapper workOrderMapper;

    private final DispatchProperties properties = new DispatchProperties();

    private DispatchService dispatchService;

    private final CurrentUser dispatcher =
            new CurrentUser(UUID.randomUUID(), "dispatcher@example.com", RoleName.DISPATCHER);

    @BeforeEach
    void setUp() {
        properties.setMaxOpenTicketsPerTechnician(8);
        dispatchService =
                new DispatchService(workOrders, users, workOrderService, workOrderMapper, properties);
    }

    /** {@link WorkOrder}'s constructor is protected; subclass to instantiate it from this package. */
    private static WorkOrder newWorkOrder() {
        return new WorkOrder() {};
    }

    /** {@link User}/{@link Role} constructors are protected; subclass to instantiate them here. */
    private static User technician(UUID id, String username, boolean active, RoleName roleName) {
        Role role = new Role() {};
        role.setName(roleName);
        User user = new User() {};
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setFullName("Tech " + username);
        user.setRole(role);
        user.setActive(active);
        return user;
    }

    private static TechnicianLoad load(UUID id, long tickets) {
        return new TechnicianLoad() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getUsername() {
                return "tech-" + id.toString().substring(0, 8);
            }

            @Override
            public String getFullName() {
                return "Technician";
            }

            @Override
            public long getTicketLoad() {
                return tickets;
            }
        };
    }

    // ------------------------------------------------------------------
    // Workload rule
    // ------------------------------------------------------------------

    @Test
    void assignSucceedsBelowTheLimit() {
        UUID techId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        User tech = technician(techId, "tech1", true, RoleName.TECHNICIAN);
        WorkOrder assigned = newWorkOrder();
        when(users.findById(techId)).thenReturn(Optional.of(tech));
        when(workOrders.countByAssigneeIdAndStatusIn(techId, ACTIVE_STATES)).thenReturn(7L);
        when(workOrderService.assignTicket(ticketId, techId, dispatcher)).thenReturn(assigned);

        WorkOrder result = dispatchService.assign(techId, ticketId, dispatcher);

        assertThat(result).isSameAs(assigned);
        verify(workOrders).countByAssigneeIdAndStatusIn(techId, ACTIVE_STATES);
        verify(workOrderService).assignTicket(ticketId, techId, dispatcher);
    }

    @Test
    void assignAtTheLimitIsRejected() {
        UUID techId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        User tech = technician(techId, "busy-tech", true, RoleName.TECHNICIAN);
        when(users.findById(techId)).thenReturn(Optional.of(tech));
        when(workOrders.countByAssigneeIdAndStatusIn(techId, ACTIVE_STATES)).thenReturn(8L);

        assertThatThrownBy(() -> dispatchService.assign(techId, ticketId, dispatcher))
                .isInstanceOf(WorkloadLimitExceededException.class)
                .satisfies(e -> {
                    WorkloadLimitExceededException ex = (WorkloadLimitExceededException) e;
                    assertThat(ex.getTechnicianId()).isEqualTo(techId);
                    assertThat(ex.getLimit()).isEqualTo(8);
                    assertThat(ex.getCurrentLoad()).isEqualTo(8);
                    assertThat(ex.getCode()).isEqualTo("workload_limit_exceeded");
                });

        // The work-order module is never touched once the rule rejects the assignment.
        verify(workOrderService, never()).assignTicket(any(), any(), any());
    }

    @Test
    void assignAboveTheLimitIsRejected() {
        UUID techId = UUID.randomUUID();
        User tech = technician(techId, "swamped-tech", true, RoleName.TECHNICIAN);
        when(users.findById(techId)).thenReturn(Optional.of(tech));
        when(workOrders.countByAssigneeIdAndStatusIn(techId, ACTIVE_STATES)).thenReturn(12L);

        assertThatThrownBy(() -> dispatchService.assign(techId, UUID.randomUUID(), dispatcher))
                .isInstanceOf(WorkloadLimitExceededException.class);

        verify(workOrderService, never()).assignTicket(any(), any(), any());
    }

    @Test
    void workloadLimitIsConfigurable() {
        properties.setMaxOpenTicketsPerTechnician(2);
        UUID techId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        User tech = technician(techId, "tech2", true, RoleName.TECHNICIAN);
        when(users.findById(techId)).thenReturn(Optional.of(tech));
        when(workOrders.countByAssigneeIdAndStatusIn(techId, ACTIVE_STATES)).thenReturn(2L);

        assertThatThrownBy(() -> dispatchService.assign(techId, ticketId, dispatcher))
                .isInstanceOf(WorkloadLimitExceededException.class)
                .satisfies(e -> assertThat(((WorkloadLimitExceededException) e).getLimit()).isEqualTo(2));
    }

    // ------------------------------------------------------------------
    // Assignee eligibility
    // ------------------------------------------------------------------

    @Test
    void assignToDeactivatedUserIsRejected() {
        UUID techId = UUID.randomUUID();
        User tech = technician(techId, "ex-tech", false, RoleName.TECHNICIAN);
        when(users.findById(techId)).thenReturn(Optional.of(tech));

        assertThatThrownBy(() -> dispatchService.assign(techId, UUID.randomUUID(), dispatcher))
                .isInstanceOf(UserNotAssignableException.class)
                .hasMessageContaining("deactivated");

        verify(workOrders, never()).countByAssigneeIdAndStatusIn(any(), any());
    }

    @Test
    void assignToNonTechnicianIsRejected() {
        UUID userId = UUID.randomUUID();
        User dispatcherUser = technician(userId, "boss", true, RoleName.DISPATCHER);
        when(users.findById(userId)).thenReturn(Optional.of(dispatcherUser));

        assertThatThrownBy(() -> dispatchService.assign(userId, UUID.randomUUID(), dispatcher))
                .isInstanceOf(UserNotAssignableException.class)
                .hasMessageContaining("TECHNICIAN");
    }

    @Test
    void assignToUnknownUserIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dispatchService.assign(userId, UUID.randomUUID(), dispatcher))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Response + directory
    // ------------------------------------------------------------------

    @Test
    void assignResponseMapsInsideTheSameCall() {
        UUID techId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        User tech = technician(techId, "tech3", true, RoleName.TECHNICIAN);
        WorkOrder assigned = newWorkOrder();
        WorkOrderResponse dto = mock(WorkOrderResponse.class);
        when(users.findById(techId)).thenReturn(Optional.of(tech));
        when(workOrders.countByAssigneeIdAndStatusIn(techId, ACTIVE_STATES)).thenReturn(0L);
        when(workOrderService.assignTicket(ticketId, techId, dispatcher)).thenReturn(assigned);
        when(workOrderMapper.toResponse(assigned)).thenReturn(dto);

        assertThat(dispatchService.assignResponse(techId, ticketId, dispatcher)).isSameAs(dto);
    }

    @Test
    void listTechniciansIncludesLoadAndDefaultsMissingToZero() {
        UUID techId = UUID.randomUUID();
        UUID idleId = UUID.randomUUID();
        User tech = technician(techId, "loaded", true, RoleName.TECHNICIAN);
        User idle = technician(idleId, "idle", true, RoleName.TECHNICIAN);
        when(workOrders.loadByTechnician(ACTIVE_STATES)).thenReturn(List.of(load(techId, 5L)));
        when(users.findActiveByRoleName(RoleName.TECHNICIAN)).thenReturn(List.of(tech, idle));

        List<TechnicianResponse> result = dispatchService.listTechnicians();

        assertThat(result).hasSize(2);
        assertThat(result)
                .filteredOn(r -> r.id().equals(techId))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.username()).isEqualTo("loaded");
                    assertThat(r.openTickets()).isEqualTo(5L);
                });
        assertThat(result)
                .filteredOn(r -> r.id().equals(idleId))
                .singleElement()
                .satisfies(r -> assertThat(r.openTickets()).isZero());
    }
}
