package com.fieldwork.ops.sla;

import com.fieldwork.ops.common.security.SecurityUtils;
import com.fieldwork.ops.sla.dto.BreachResponse;
import com.fieldwork.ops.sla.dto.SlaPolicyRequest;
import com.fieldwork.ops.sla.dto.SlaPolicyResponse;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SLA policy administration and breach reads. Thin by design: all
 * rules (target validation, active-policy uniqueness) live in
 * {@link SlaService}.
 */
@RestController
@RequestMapping("/api/v1/sla")
@RequiredArgsConstructor
public class SlaController {

    private final SlaService slaService;
    private final SlaMapper mapper;

    @GetMapping("/policies")
    @PreAuthorize("isAuthenticated()")
    public List<SlaPolicyResponse> listPolicies() {
        return slaService.listPolicies().stream().map(mapper::toResponse).toList();
    }

    /** SLA policy writes are admin-only. */
    @PostMapping("/policies")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SlaPolicyResponse> createPolicy(@Valid @RequestBody SlaPolicyRequest request) {
        SlaPolicy policy = slaService.createPolicy(
                request.name(),
                request.priority(),
                request.category(),
                request.responseMinutes(),
                request.resolutionMinutes(),
                request.active() == null || request.active(),
                SecurityUtils.requireCurrentUser().email());
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toResponse(policy));
    }

    /** SLA policy writes are admin-only. */
    @PutMapping("/policies/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SlaPolicyResponse updatePolicy(
            @PathVariable UUID id, @Valid @RequestBody SlaPolicyRequest request) {
        return mapper.toResponse(slaService.updatePolicy(
                id,
                request.name(),
                request.priority(),
                request.category(),
                request.responseMinutes(),
                request.resolutionMinutes(),
                request.active(),
                SecurityUtils.requireCurrentUser().email()));
    }

    /**
     * Breaches whose deadline passed in [{@code from}, {@code to}]
     * (ISO-8601). Both bounds are optional; omitting them returns every
     * recorded breach, newest first.
     */
    @GetMapping("/breaches")
    @PreAuthorize("isAuthenticated()")
    public List<BreachResponse> listBreaches(
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to) {
        return slaService.listBreaches(from, to).stream().map(mapper::toBreachResponse).toList();
    }
}
