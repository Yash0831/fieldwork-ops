package com.fieldwork.ops.dispatch.dto;

import java.util.UUID;

/**
 * One row of the dispatcher technician directory: an active
 * technician, their team, and how many OPEN/IN_PROGRESS tickets they
 * currently hold — everything dispatch needs to pick an assignee.
 */
public record TechnicianResponse(
        UUID id, String username, String fullName, String teamName, long openTickets) {}
