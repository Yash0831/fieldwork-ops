package com.fieldwork.ops.workorder;

import java.util.UUID;

/**
 * Per-technician ticket-load projection for the dashboard and the
 * dispatcher technician directory. Interface-based so Spring Data
 * derives it straight from the {@code loadByTechnician} query.
 */
public interface TechnicianLoad {

    UUID getId();

    String getUsername();

    String getFullName();

    long getTicketLoad();
}
