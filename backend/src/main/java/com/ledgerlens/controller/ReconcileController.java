package com.ledgerlens.controller;

import com.ledgerlens.dto.ExceptionView;
import com.ledgerlens.dto.MatchView;
import com.ledgerlens.dto.ReconcileSummary;
import com.ledgerlens.dto.WaterfallStep;
import com.ledgerlens.service.ReconciliationService;
import com.ledgerlens.service.WaterfallService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reconcile")
public class ReconcileController {

    private final ReconciliationService reconciliationService;
    private final WaterfallService waterfallService;

    public ReconcileController(ReconciliationService reconciliationService, WaterfallService waterfallService) {
        this.reconciliationService = reconciliationService;
        this.waterfallService = waterfallService;
    }

    @PostMapping("/{batchId}")
    public ReconcileSummary reconcile(@PathVariable UUID batchId) {
        return reconciliationService.reconcile(batchId);
    }

    @GetMapping("/{batchId}/summary")
    public ReconcileSummary summary(@PathVariable UUID batchId) {
        return reconciliationService.summary(batchId);
    }

    @GetMapping("/{batchId}/exceptions")
    public List<ExceptionView> exceptions(@PathVariable UUID batchId) {
        return reconciliationService.exceptions(batchId);
    }

    @GetMapping("/{batchId}/waterfall")
    public List<WaterfallStep> waterfall(@PathVariable UUID batchId) {
        return waterfallService.waterfall(batchId);
    }

    @GetMapping("/{batchId}/matches")
    public Page<MatchView> matches(@PathVariable UUID batchId, @PageableDefault(size = 50) Pageable pageable) {
        return reconciliationService.matches(batchId, pageable);
    }
}
