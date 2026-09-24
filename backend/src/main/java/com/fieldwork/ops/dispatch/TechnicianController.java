package com.fieldwork.ops.dispatch;

import com.fieldwork.ops.dispatch.dto.TechnicianResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispatcher technician directory. Dispatchers need to see who can
 * take work (and their current load) without ADMIN privileges; the
 * full user-management surface stays behind
 * {@code AdminUserController}.
 */
@RestController
@RequestMapping("/api/v1/technicians")
@PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
@RequiredArgsConstructor
public class TechnicianController {

    private final DispatchService dispatchService;

    @GetMapping
    public List<TechnicianResponse> list() {
        return dispatchService.listTechnicians();
    }
}
