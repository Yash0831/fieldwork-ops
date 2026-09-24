package com.fieldwork.ops.dispatch;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workload-guard tuning for dispatch.
 *
 * <p>Bound from {@code dispatch.*} in application.yml; the default (8)
 * applies when nothing is configured. Raise it for senior technicians or
 * lower it during incident response without a code change.
 */
@Component
@ConfigurationProperties(prefix = "dispatch")
@Getter
@Setter
public class DispatchProperties {

    /**
     * Maximum number of OPEN or IN_PROGRESS tickets a single technician
     * may hold. Assignment beyond this is rejected with
     * {@code WorkloadLimitExceededException}.
     */
    private int maxOpenTicketsPerTechnician = 8;
}
