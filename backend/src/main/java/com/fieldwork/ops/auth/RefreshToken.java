package com.fieldwork.ops.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A persisted refresh-token record. Only the SHA-256 <em>hash</em> of
 * the token is stored — the raw token is shown to the client once, at
 * issuance, and never again. Rotation invalidates the old row on every
 * use ({@code revoked = true}); a replayed revoked token is treated as
 * possible theft and causes the whole token family to be revoked (see
 * {@code AuthService}).
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** SHA-256 hex of the raw token (64 chars), unique. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** True when the token may still be exchanged for a new pair. */
    public boolean isUsableAt(OffsetDateTime now) {
        return !revoked && expiresAt.isAfter(now);
    }

    /** Creates a fresh, unrevoked refresh-token record for {@code user}. */
    public static RefreshToken issue(User user, String tokenHash, OffsetDateTime expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(tokenHash);
        token.setExpiresAt(expiresAt);
        return token;
    }
}
