/**
 * Work-order lifecycle: creation, guarded status transitions, assignment,
 * comments, status history, and idempotent intake.
 *
 * The state machine lives here. Nothing outside this module may change a
 * work order's status directly.
 */
package com.fieldwork.ops.workorder;
