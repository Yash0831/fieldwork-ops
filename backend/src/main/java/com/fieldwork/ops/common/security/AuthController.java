package com.fieldwork.ops.common.security;

import com.fieldwork.ops.common.security.dto.LoginRequest;
import com.fieldwork.ops.common.security.dto.RefreshRequest;
import com.fieldwork.ops.common.security.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints. Public by design — the security filter
 * chain permits {@code /api/v1/auth/**} without a token.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Verifies credentials and returns the initial token pair.
     * Failures return 401 with the standard error envelope (the message
     * never reveals whether the email exists).
     */
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    /**
     * Rotates a refresh token: the presented token is revoked and a new
     * pair is returned. Replaying an old token fails with 401 and
     * revokes the whole token family.
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /** Revokes a refresh token. Idempotent — always returns 204. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }
}
