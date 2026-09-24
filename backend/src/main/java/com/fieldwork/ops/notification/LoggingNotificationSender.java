package com.fieldwork.ops.notification;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 6 stand-in for real delivery: logs what <em>would</em> be sent
 * and reports success, so the outbox → worker → SENT flow works
 * end-to-end before SMTP exists. Never throws.
 *
 * <p>Replaced by a real SMTP implementation later; the
 * {@link NotificationSender} interface is the seam, so nothing else
 * changes.
 */
@Component
@Slf4j
public class LoggingNotificationSender implements NotificationSender {

    @Override
    public void send(Notification notification) {
        UUID workOrderId = notification.getRelatedWorkOrder() != null
                ? notification.getRelatedWorkOrder().getId()
                : null;
        log.info(
                "SEND notification channel={} to={} subject='{}' eventType={} workOrderId={} "
                        + "(no mail provider configured — logged only)",
                notification.getChannel(),
                notification.getRecipientEmail(),
                notification.getSubject(),
                notification.getEventType(),
                workOrderId);
    }
}
