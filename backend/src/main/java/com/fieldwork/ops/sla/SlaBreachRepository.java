package com.fieldwork.ops.sla;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SlaBreachRepository extends JpaRepository<SlaBreach, UUID> {

    List<SlaBreach> findByWorkOrderId(UUID workOrderId);

    Optional<SlaBreach> findByWorkOrderIdAndBreachType(UUID workOrderId, BreachType breachType);

    List<SlaBreach> findByBreachedAtBetween(OffsetDateTime from, OffsetDateTime to);

    List<SlaBreach> findByResolvedAtIsNull();
}
