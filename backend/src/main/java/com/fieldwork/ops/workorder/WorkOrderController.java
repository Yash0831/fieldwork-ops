package com.fieldwork.ops.workorder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fieldwork.ops.attachment.AttachmentService;
import com.fieldwork.ops.auth.RoleName;
import com.fieldwork.ops.common.security.CurrentUser;
import com.fieldwork.ops.common.security.SecurityUtils;
import com.fieldwork.ops.dispatch.DispatchService;
import com.fieldwork.ops.workorder.dto.AssignRequest;
import com.fieldwork.ops.workorder.dto.CommentRequest;
import com.fieldwork.ops.workorder.dto.CommentResponse;
import com.fieldwork.ops.workorder.dto.CreateWorkOrderRequest;
import com.fieldwork.ops.workorder.dto.StatusHistoryResponse;
import com.fieldwork.ops.workorder.dto.StatusTransitionRequest;
import com.fieldwork.ops.workorder.dto.WorkOrderListResponse;
import com.fieldwork.ops.workorder.dto.WorkOrderResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Work-order REST API. Thin by design: every endpoint maps DTOs and
 * delegates to {@link WorkOrderService} (lifecycle) or
 * {@link DispatchService} (assignment with the workload rule).
 *
 * <p>RBAC is enforced in two layers: {@code @PreAuthorize} role gates
 * on each endpoint, plus ownership checks inside
 * {@link WorkOrderService} (a technician may only touch assigned
 * tickets, a requester only their own) — annotations alone cannot
 * express those. The authenticated principal is resolved once per
 * request via {@link SecurityUtils} and passed down as the actor, so
 * no endpoint trusts a client-supplied user id. Attachment
 * upload/download/delete live in {@code AttachmentController} (the
 * attachment module); this controller only embeds the attachment
 * metadata list in the ticket detail.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final DispatchService dispatchService;
    private final AttachmentService attachmentService;
    private final WorkOrderMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Creates a ticket. Honors the {@code Idempotency-Key} header: a
     * replay of a completed key returns the original 201 response body
     * verbatim instead of creating a duplicate.
     *
     * <p>A REQUESTER always files as themselves — a client-supplied
     * {@code requesterId} that differs from the principal is ignored.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('REQUESTER', 'ADMIN')")
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateWorkOrderRequest request) {
        CurrentUser actor = SecurityUtils.requireCurrentUser();
        UUID requesterId = actor.role() == RoleName.REQUESTER ? actor.id() : request.requesterId();
        CreateWorkOrderCommand command = new CreateWorkOrderCommand(
                request.title(),
                request.description(),
                request.priority(),
                request.category(),
                requesterId,
                request.teamId(),
                request.estimatedHours());
        WorkOrderCreation result =
                workOrderService.create(command, idempotencyKey, actor.email(), this::serializeResponse);

        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(result.replayed() ? result.responseStatus() : HttpStatus.CREATED.value());
        if (!result.replayed()) {
            response.location(URI.create("/api/v1/work-orders/" + result.workOrder().getId()));
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(result.responseBody());
    }

    /**
     * Filtered, paged ticket queue. Defaults: page 0, 20 per page, newest
     * first; the page size is capped at 100 by the service.
     *
     * <p>REQUESTERs always see only their own tickets. Any role may pass
     * {@code mine=true} to scope the queue to tickets they requested.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public WorkOrderListResponse list(
            @RequestParam(required = false) WorkOrderStatus status,
            @RequestParam(required = false) WorkOrderPriority priority,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) UUID requesterId,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        CurrentUser actor = SecurityUtils.requireCurrentUser();
        UUID effectiveRequester = requesterId;
        if (actor.role() == RoleName.REQUESTER || mine) {
            effectiveRequester = actor.id();
        }
        return workOrderService.searchResponses(
                status, priority, teamId, assigneeId, effectiveRequester, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public WorkOrderResponse getById(@PathVariable UUID id) {
        CurrentUser actor = SecurityUtils.requireCurrentUser();
        return workOrderService.getDetail(id, actor, () -> attachmentService.listForTicket(id, actor));
    }

    /**
     * Transitions a ticket. TECHNICIANs may only transition tickets
     * assigned to them — enforced in the service layer.
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('TECHNICIAN', 'ADMIN')")
    public WorkOrderResponse transitionStatus(
            @PathVariable UUID id, @Valid @RequestBody StatusTransitionRequest request) {
        return workOrderService.transitionStatusResponse(
                id, request.toStatus(), SecurityUtils.requireCurrentUser(), request.note());
    }

    /**
     * Assigns a ticket to a technician. Runs through
     * {@link DispatchService}, so the workload rule applies (422 when
     * the technician is at capacity, deactivated, or not a technician).
     */
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
    public WorkOrderResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return dispatchService.assignResponse(
                request.technicianId(), id, SecurityUtils.requireCurrentUser());
    }

    /**
     * Adds a comment. The author is the authenticated principal;
     * ownership (assigned ticket / own ticket) is enforced in the
     * service layer.
     */
    @PostMapping("/{id}/comments")
    @PreAuthorize("hasAnyRole('REQUESTER', 'TECHNICIAN', 'ADMIN')")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID id, @Valid @RequestBody CommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(workOrderService.addCommentResponse(
                        id, request.body(), request.internal(), SecurityUtils.requireCurrentUser()));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("isAuthenticated()")
    public List<StatusHistoryResponse> history(@PathVariable UUID id) {
        return workOrderService.getHistoryResponses(id, SecurityUtils.requireCurrentUser());
    }

    /**
     * Renders the creation response inside the service transaction, so
     * the bytes stored on the idempotency row match the live 201 body
     * exactly (lazy associations are still available here).
     */
    private String serializeResponse(WorkOrder workOrder) {
        try {
            return objectMapper.writeValueAsString(mapper.toResponse(workOrder));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize work-order creation response", e);
        }
    }
}
