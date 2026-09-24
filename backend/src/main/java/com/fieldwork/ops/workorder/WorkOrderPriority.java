package com.fieldwork.ops.workorder;

/**
 * Work-order priority. Each level maps to an SLA policy (response and
 * resolution targets); see the sla module. Mirrors
 * chk_work_orders_priority.
 */
public enum WorkOrderPriority {
    P1,
    P2,
    P3,
    P4
}
