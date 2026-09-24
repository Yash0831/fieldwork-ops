/**
 * Notifications: domain-event listeners create notification rows; a retry
 * worker delivers them with exponential backoff and moves exhausted ones
 * to a dead-letter state. All listeners are idempotent.
 */
package com.fieldwork.ops.notification;
