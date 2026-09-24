package com.fieldwork.ops.auth;

/**
 * The fixed set of roles recognised by the RBAC layer. Mirrors the
 * chk_roles_name CHECK constraint; adding a value requires both a
 * migration and an enum entry.
 */
public enum RoleName {
    ADMIN,
    DISPATCHER,
    TECHNICIAN,
    REQUESTER
}
