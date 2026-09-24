package com.fieldwork.ops.common.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    /** "Show me everything that happened to this entity", newest last. */
    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtAsc(String entityType, String entityId);

    List<AuditLog> findByActorIdOrderByOccurredAtDesc(UUID actorId);

    List<AuditLog> findByActionOrderByOccurredAtDesc(String action);
}
