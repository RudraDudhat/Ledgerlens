package com.ledgerlens.controller;

import com.ledgerlens.dto.HealthHistoryPoint;
import com.ledgerlens.dto.HealthReport;
import com.ledgerlens.service.HealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    /** Eight points is what the sparkline shows; asking for more would only be thrown away. */
    private static final int HISTORY_POINTS = 8;

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/{batchId}")
    public HealthReport health(@PathVariable UUID batchId) {
        return healthService.report(batchId);
    }

    @GetMapping("/{batchId}/history")
    public List<HealthHistoryPoint> history(@PathVariable UUID batchId) {
        return healthService.history(batchId, HISTORY_POINTS);
    }
}
