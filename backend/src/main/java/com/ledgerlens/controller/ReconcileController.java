package com.ledgerlens.controller;

import com.ledgerlens.dto.MatchView;
import com.ledgerlens.dto.ReconcileSummary;
import com.ledgerlens.service.ReconciliationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reconcile")
public class ReconcileController {

    private final ReconciliationService reconciliationService;

    public ReconcileController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/{batchId}")
    public ReconcileSummary reconcile(@PathVariable UUID batchId) {
        return reconciliationService.reconcile(batchId);
    }

    @GetMapping("/{batchId}/summary")
    public ReconcileSummary summary(@PathVariable UUID batchId) {
        return reconciliationService.summary(batchId);
    }

    @GetMapping("/{batchId}/matches")
    public Page<MatchView> matches(@PathVariable UUID batchId, @PageableDefault(size = 50) Pageable pageable) {
        return reconciliationService.matches(batchId, pageable);
    }
}
