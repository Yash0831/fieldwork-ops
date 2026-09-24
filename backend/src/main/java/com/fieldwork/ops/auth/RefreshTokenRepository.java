package com.fieldwork.ops.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /** Lookup by the SHA-256 hash of the raw token — the raw token itself is never stored. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every live token of a user in one statement: used when a
     * revoked token is replayed (possible theft) and when an admin
     * deactivates the account.
     */
    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.user.id = :userId and r.revoked = false")
    void revokeAllByUserId(@Param("userId") UUID userId);
}
