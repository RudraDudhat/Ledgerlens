package com.ledgerlens.controller;

import com.ledgerlens.dto.MetricsReport;
import com.ledgerlens.service.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService metricsService;

    public MetricsController(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/{batchId}")
    public MetricsReport metrics(@PathVariable UUID batchId) {
        return metricsService.metrics(batchId);
    }
}
