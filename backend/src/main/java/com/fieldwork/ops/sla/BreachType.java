package com.fieldwork.ops.sla;

/**
 * Which SLA target was breached: the first-response target or the
 * resolution target. Mirrors chk_sla_breaches_breach_type.
 */
public enum BreachType {
    RESPONSE,
    RESOLUTION
}
