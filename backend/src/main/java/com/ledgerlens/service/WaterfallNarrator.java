package com.ledgerlens.service;

import com.ledgerlens.dto.NarrativeResponse;
import com.ledgerlens.dto.WaterfallStep;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Puts the waterfall into words. The numbers are computed before the model is ever called, and only
 * the labels and final amounts are handed over, so there is nothing here for it to get wrong
 * arithmetically. Steps worth nothing are dropped rather than narrated as zero.
 */
@Service
public class WaterfallNarrator {

    private final LlmGateway llm;
    private final WaterfallService waterfallService;
    private final String template;

    public WaterfallNarrator(LlmGateway llm, WaterfallService waterfallService) {
        this.llm = llm;
        this.waterfallService = waterfallService;
        this.template = LlmGateway.loadPrompt("waterfall-narrator.txt");
    }

    public NarrativeResponse narrate(UUID batchId) {
        List<WaterfallStep> steps = waterfallService.waterfall(batchId);
        String prompt = template.replace("{{waterfall}}", render(steps));
        return new NarrativeResponse(llm.complete(batchId, "LLM_NARRATE", prompt).trim());
    }

    public static String render(List<WaterfallStep> steps) {
        return steps.stream()
                .filter(step -> step.amount().signum() != 0)
                .map(step -> "%s: %s".formatted(step.label(), step.amount().toPlainString()))
                .collect(Collectors.joining("\n"));
    }
}
