package com.fieldwork.ops.workorder;

import com.fieldwork.ops.auth.Team;
import com.fieldwork.ops.auth.User;
import com.fieldwork.ops.workorder.dto.CommentResponse;
import com.fieldwork.ops.workorder.dto.StatusHistoryResponse;
import com.fieldwork.ops.workorder.dto.WorkOrderListResponse;
import com.fieldwork.ops.workorder.dto.WorkOrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * Entity → DTO mapping for the work-order web layer. Kept in the
 * workorder module (next to the entities) so controllers stay thin;
 * all mapping is null-safe for optional associations.
 */
@Component
public class WorkOrderMapper {

    public WorkOrderResponse toResponse(WorkOrder workOrder) {
        return new WorkOrderResponse(
                workOrder.getId(),
                workOrder.getTicketNumber(),
                workOrder.getTitle(),
                workOrder.getDescription(),
                workOrder.getStatus(),
                workOrder.getPriority(),
                workOrder.getCategory(),
                toUserSummary(workOrder.getRequester()),
                toUserSummary(workOrder.getAssignee()),
                toTeamSummary(workOrder.getTeam()),
                new WorkOrderResponse.SlaInfo(
                        workOrder.getResponseDueAt(),
                        workOrder.getResolutionDueAt(),
                        workOrder.getDueAt(),
                        workOrder.getStatus() == WorkOrderStatus.ON_HOLD,
                        workOrder.getSlaPausedSeconds()),
                workOrder.getEstimatedHours(),
                workOrder.getRespondedAt(),
                workOrder.getResolvedAt(),
                workOrder.getClosedAt(),
                workOrder.getVersion() == null ? 0L : workOrder.getVersion(),
                workOrder.getCreatedAt(),
                workOrder.getUpdatedAt(),
                workOrder.getCreatedBy(),
                workOrder.getUpdatedBy());
    }

    public WorkOrderListResponse toListResponse(Page<WorkOrder> page) {
        return new WorkOrderListResponse(
                page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    public CommentResponse toCommentResponse(Comment comment) {
        return new CommentResponse(
                comment.getId(),
                comment.getBody(),
                comment.isInternal(),
                toUserSummary(comment.getAuthor()),
                comment.getCreatedAt());
    }

    public StatusHistoryResponse toHistoryResponse(WorkOrderStatusHistory entry) {
        return new StatusHistoryResponse(
                entry.getId(),
                entry.getFromStatus(),
                entry.getToStatus(),
                toUserSummary(entry.getChangedBy()),
                entry.getChangedAt(),
                entry.getNote());
    }

    private WorkOrderResponse.UserSummary toUserSummary(User user) {
        return user == null
                ? null
                : new WorkOrderResponse.UserSummary(user.getId(), user.getUsername(), user.getFullName());
    }

    private WorkOrderResponse.TeamSummary toTeamSummary(Team team) {
        return team == null ? null : new WorkOrderResponse.TeamSummary(team.getId(), team.getName());
    }
}
