package com.fieldwork.ops.workorder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Attachment endpoints arrive in Phase 7.
 */
@RestController
@RequestMapping("/api/v1/work-orders")
@RequiredArgsConstructor
public class WorkOrderController {

    private final WorkOrderService workOrderService;
    private final DispatchService dispatchService;
    private final WorkOrderMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * Creates a ticket. Honors the {@code Idempotency-Key} header: a
     * replay of a completed key returns the original 201 response body
     * verbatim instead of creating a duplicate.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateWorkOrderRequest request) {
        CreateWorkOrderCommand command = new CreateWorkOrderCommand(
                request.title(),
                request.description(),
                request.priority(),
                request.category(),
                request.requesterId(),
                request.teamId(),
                request.estimatedHours());
        // No authentication in Phase 4 — the actor is unresolved (null);
        // Phase 5 will pass the authenticated principal as requestedBy.
        WorkOrderCreation result =
                workOrderService.create(command, idempotencyKey, null, this::serializeResponse);

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
     */
    @GetMapping
    public WorkOrderListResponse list(
            @RequestParam(required = false) WorkOrderStatus status,
            @RequestParam(required = false) WorkOrderPriority priority,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID assigneeId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return mapper.toListResponse(
                workOrderService.search(status, priority, teamId, assigneeId, pageable));
    }

    @GetMapping("/{id}")
    public WorkOrderResponse getById(@PathVariable UUID id) {
        return mapper.toResponse(workOrderService.getById(id));
    }

    @PatchMapping("/{id}/status")
    public WorkOrderResponse transitionStatus(
            @PathVariable UUID id, @Valid @RequestBody StatusTransitionRequest request) {
        return mapper.toResponse(
                workOrderService.transitionStatus(id, request.toStatus(), null, request.note()));
    }

    /**
     * Assigns a ticket to a technician. Runs through
     * {@link DispatchService}, so the workload rule applies (422 when
     * the technician is at capacity).
     */
    @PostMapping("/{id}/assign")
    public WorkOrderResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return mapper.toResponse(dispatchService.assign(request.technicianId(), id, null));
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentResponse> addComment(
            @PathVariable UUID id, @Valid @RequestBody CommentRequest request) {
        Comment comment =
                workOrderService.addComment(id, request.authorId(), request.body(), request.internal(), null);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toCommentResponse(comment));
    }

    @GetMapping("/{id}/history")
    public List<StatusHistoryResponse> history(@PathVariable UUID id) {
        return workOrderService.getHistory(id).stream().map(mapper::toHistoryResponse).toList();
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
