package com.fieldwork.ops.workorder.dto;

import java.util.List;

/**
 * Page wrapper for {@code GET /api/v1/work-orders}. Mirrors the
 * pagination fields clients need without leaking Spring Data types
 * into the API contract.
 */
public record WorkOrderListResponse(
        List<WorkOrderResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {}
