package com.ledgerlens.service;

import com.ledgerlens.dto.ClassifiedException;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.ExceptionOrigin;
import com.ledgerlens.entity.ExceptionRecord;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.entity.MatchRecord;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.repository.MatchRecordRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Asks the model about the leftovers, and only the leftovers.
 *
 * <p>The deterministic rules run first and settle everything they can. Whatever they file as UNKNOWN
 * arrives here with up to ten candidate rows pulled by query. A reply is accepted only if it names a
 * real status and stays inside the confidence range; anything malformed leaves the record as UNKNOWN,
 * so a guess never overwrites an honest admission that the rules could not tell.
 */
@Service
public class ExceptionClassifier {

    private static final Logger log = LoggerFactory.getLogger(ExceptionClassifier.class);
    private static final int MAX_CANDIDATES = 10;
    private static final int MAX_REASON_LENGTH = 500;

    private final LlmGateway llm;
    private final ExceptionRecordRepository exceptionRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankEntryRepository bankEntryRepository;
    private final MatchRecordRepository matchRepository;
    private final String template;

    public ExceptionClassifier(LlmGateway llm,
                               ExceptionRecordRepository exceptionRepository,
                               SettlementLineRepository settlementLineRepository,
                               SettlementBatchRepository settlementBatchRepository,
                               BankEntryRepository bankEntryRepository,
                               MatchRecordRepository matchRepository) {
        this.llm = llm;
        this.exceptionRepository = exceptionRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.bankEntryRepository = bankEntryRepository;
        this.matchRepository = matchRepository;
        this.template = LlmGateway.loadPrompt("exception-classifier.txt");
    }

    @Transactional
    public int classifyUnresolved(UUID batchId) {
        List<ExceptionRecord> unresolved = exceptionRepository.findByBatchIdOrderById(batchId).stream()
                .filter(record -> record.getStatus() == ExceptionStatus.UNKNOWN)
                .filter(record -> record.getOrigin() == ExceptionOrigin.RULE)
                .toList();
        if (unresolved.isEmpty() || !llm.available()) {
            return 0;
        }

        List<String> candidates = candidateRows(batchId);
        int reclassified = 0;
        for (ExceptionRecord record : unresolved) {
            String prompt = template
                    .replace("{{record}}", describe(record))
                    .replace("{{candidates}}", String.join("\n", candidates));
            try {
                ClassifiedException verdict =
                        llm.completeAs(batchId, "LLM_CLASSIFY", prompt, ClassifiedException.class);
                if (apply(record, verdict)) {
                    reclassified++;
                }
            } catch (RuntimeException e) {
                log.warn("classifier failed for {}, leaving it UNKNOWN: {}", record.getEntityRef(), e.toString());
            }
        }
        exceptionRepository.saveAll(unresolved);
        return reclassified;
    }

    private boolean apply(ExceptionRecord record, ClassifiedException verdict) {
        if (verdict == null || verdict.status() == null || verdict.reason() == null
                || verdict.reason().isBlank()) {
            return false;
        }
        ExceptionStatus status;
        try {
            status = ExceptionStatus.valueOf(verdict.status().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("classifier returned status {} which is not a reconciliation status, leaving {} UNKNOWN",
                    verdict.status(), record.getEntityRef());
            return false;
        }
        if (status == ExceptionStatus.UNKNOWN || status == ExceptionStatus.MATCHED) {
            return false;
        }

        double confidence = Math.max(0.0, Math.min(1.0, verdict.confidence()));
        record.setStatus(status);
        record.setReason(verdict.reason().length() > MAX_REASON_LENGTH
                ? verdict.reason().substring(0, MAX_REASON_LENGTH)
                : verdict.reason());
        record.setConfidence(BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP));
        record.setOrigin(ExceptionOrigin.LLM);
        return true;
    }

    private static String describe(ExceptionRecord record) {
        return "entity=%s currentStatus=%s ruleSaid=%s sourceRowIds=%s"
                .formatted(record.getEntityRef(), record.getStatus(), record.getReason(), record.getSourceRowIds());
    }

    /** Unpaired rows are the only ones that can explain an unpaired record, so those are what it sees. */
    private List<String> candidateRows(UUID batchId) {
        Set<Long> creditedSettlementIds = new HashSet<>();
        Set<Long> claimedBankIds = new HashSet<>();
        for (MatchRecord match : matchRepository.findByBatchIdOrderById(batchId)) {
            if (match.getBankEntryRowId() != null) {
                creditedSettlementIds.add(match.getSettlementBatchRowId());
                claimedBankIds.add(match.getBankEntryRowId());
            }
        }

        List<String> rows = new ArrayList<>();
        for (SettlementBatch settlement : settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId)) {
            if (rows.size() >= MAX_CANDIDATES) {
                return rows;
            }
            if (!creditedSettlementIds.contains(settlement.getId())) {
                rows.add("settlement id=%d utr=%s settledOn=%s amount=%s (no bank credit matched)"
                        .formatted(settlement.getId(), settlement.getUtr(),
                                settlement.getSettledOn(), settlement.getAmount()));
            }
        }
        for (BankEntry entry : bankEntryRepository.findByBatchIdOrderById(batchId)) {
            if (rows.size() >= MAX_CANDIDATES) {
                return rows;
            }
            if (!claimedBankIds.contains(entry.getId())) {
                rows.add("bankCredit id=%d date=%s utr=%s amount=%s narration=%s (no settlement matched)"
                        .formatted(entry.getId(), entry.getEntryDate(), entry.getUtr(),
                                entry.getAmount(), entry.getDescription()));
            }
        }
        for (SettlementLine line : settlementLineRepository.findByBatchIdOrderById(batchId)) {
            if (rows.size() >= MAX_CANDIDATES) {
                return rows;
            }
            if (line.getOrderId() != null && line.getAmount().signum() < 0) {
                rows.add("settlementLine id=%d type=%s entity=%s orderId=%s amount=%s"
                        .formatted(line.getId(), line.getLineType(), line.getEntityId(),
                                line.getOrderId(), line.getAmount()));
            }
        }
        return rows;
    }
}
