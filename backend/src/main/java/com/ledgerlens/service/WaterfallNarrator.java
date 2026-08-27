package com.ledgerlens.service;

import com.ledgerlens.dto.NarrativeResponse;
import com.ledgerlens.dto.WaterfallStep;
import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.repository.AuditLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Puts the waterfall into words. The numbers are computed before the model is ever called, and only
 * the labels and final amounts are handed over, so there is nothing here for it to get wrong
 * arithmetically. Steps worth nothing are dropped rather than narrated as zero.
 */
@Service
public class WaterfallNarrator {

    static final String NARRATE_ACTION = "LLM_NARRATE";

    /** The gateway writes the reply at the tail of its audit detail, behind this marker. */
    private static final String OUTPUT_MARKER = " output=";

    private final LlmGateway llm;
    private final WaterfallService waterfallService;
    private final AuditLogRepository auditLogRepository;
    private final String template;

    public WaterfallNarrator(LlmGateway llm, WaterfallService waterfallService,
                             AuditLogRepository auditLogRepository) {
        this.llm = llm;
        this.waterfallService = waterfallService;
        this.auditLogRepository = auditLogRepository;
        this.template = LlmGateway.loadPrompt("waterfall-narrator.txt");
    }

    public NarrativeResponse narrate(UUID batchId) {
        List<WaterfallStep> steps = waterfallService.waterfall(batchId);
        String prompt = template.replace("{{waterfall}}", render(steps));
        return new NarrativeResponse(llm.complete(batchId, NARRATE_ACTION, prompt).trim());
    }

    /**
     * The narration this batch already got, read back out of the audit log rather than asked for
     * again.
     *
     * <p>Every model call is logged with its reply, so the log is the record of what was said about
     * this batch; a second call would spend money to produce different words about numbers that have
     * not changed. Empty when the batch was never narrated — callers show the rest without it.
     */
    public Optional<String> storedNarrative(UUID batchId) {
        List<AuditLog> entries = auditLogRepository.findByBatchIdOrderById(batchId);
        for (int index = entries.size() - 1; index >= 0; index--) {
            AuditLog entry = entries.get(index);
            if (!NARRATE_ACTION.equals(entry.getAction())) {
                continue;
            }
            String detail = entry.getDetail() == null ? "" : entry.getDetail();
            int marker = detail.indexOf(OUTPUT_MARKER);
            if (marker < 0) {
                continue;
            }
            String narrative = detail.substring(marker + OUTPUT_MARKER.length()).trim();
            if (!narrative.isEmpty()) {
                return Optional.of(narrative);
            }
        }
        return Optional.empty();
    }

    public static String render(List<WaterfallStep> steps) {
        return steps.stream()
                .filter(step -> step.amount().signum() != 0)
                .map(step -> "%s: %s".formatted(step.label(), step.amount().toPlainString()))
                .collect(Collectors.joining("\n"));
    }
}
