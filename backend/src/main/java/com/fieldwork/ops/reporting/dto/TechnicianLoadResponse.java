package com.fieldwork.ops.reporting.dto;

import java.util.UUID;

/**
 * One row of the dashboard's technician-load table: an active
 * technician and how many OPEN/IN_PROGRESS tickets they currently
 * hold.
 */
public record TechnicianLoadResponse(UUID id, String username, String fullName, long openTickets) {}
