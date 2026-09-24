package com.fieldwork.ops.workorder;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    /** Sweeps expired keys; invoked by the Phase 6 scheduled cleanup. */
    void deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
