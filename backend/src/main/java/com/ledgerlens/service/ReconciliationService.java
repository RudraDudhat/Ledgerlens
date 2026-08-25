package com.ledgerlens.service;

import com.ledgerlens.dto.ExceptionView;
import com.ledgerlens.dto.MatchView;
import com.ledgerlens.dto.ReconcileSummary;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.repository.AuditLogRepository;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.entity.MatchRecord;
import com.ledgerlens.repository.MatchRecordRepository;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** Runs the matcher against a stored batch, records the run, and answers questions about the result. */
@Service
public class ReconciliationService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final IngestBatchRepository ingestBatchRepository;
    private final MerchantOrderRepository orderRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankEntryRepository bankEntryRepository;
    private final MatchRecordRepository matchRepository;
    private final AuditLogRepository auditLogRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final ExceptionDetectionService exceptionDetectionService;
    private final DeterministicMatcher matcher;

    public ReconciliationService(IngestBatchRepository ingestBatchRepository,
                                 MerchantOrderRepository orderRepository,
                                 SettlementLineRepository settlementLineRepository,
                                 SettlementBatchRepository settlementBatchRepository,
                                 BankEntryRepository bankEntryRepository,
                                 MatchRecordRepository matchRepository,
                                 AuditLogRepository auditLogRepository,
                                 ExceptionRecordRepository exceptionRepository,
                                 ExceptionDetectionService exceptionDetectionService,
                                 DeterministicMatcher matcher) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.orderRepository = orderRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.bankEntryRepository = bankEntryRepository;
        this.matchRepository = matchRepository;
        this.auditLogRepository = auditLogRepository;
        this.exceptionRepository = exceptionRepository;
        this.exceptionDetectionService = exceptionDetectionService;
        this.matcher = matcher;
    }

    /** Re-runnable: an earlier run's matches are cleared first, so the result never accumulates. */
    @Transactional
    public ReconcileSummary reconcile(UUID batchId) {
        requireBatch(batchId);
        matchRepository.deleteByBatchId(batchId);
        matchRepository.flush();

        List<MerchantOrder> orders = orderRepository.findByBatchIdOrderById(batchId);
        List<SettlementBatch> settlements = settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId);
        List<BankEntry> bankEntries = bankEntryRepository.findByBatchIdOrderById(batchId);
        List<MatchRecord> matches = matcher.match(batchId, orders,
                settlementLineRepository.findByBatchIdOrderById(batchId), settlements, bankEntries);
        matchRepository.saveAll(matches);
        matchRepository.flush();
        exceptionDetectionService.detect(batchId);

        ReconcileSummary summary = buildSummary(batchId, orders, settlements, bankEntries, matches);
        auditLogRepository.save(auditEntry(batchId, summary));
        return summary;
    }

    @Transactional(readOnly = true)
    public ReconcileSummary summary(UUID batchId) {
        requireBatch(batchId);
        return buildSummary(batchId,
                orderRepository.findByBatchIdOrderById(batchId),
                settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId),
                bankEntryRepository.findByBatchIdOrderById(batchId),
                matchRepository.findByBatchIdOrderById(batchId));
    }

    @Transactional(readOnly = true)
    public List<ExceptionView> exceptions(UUID batchId) {
        requireBatch(batchId);
        return exceptionRepository.findByBatchIdOrderById(batchId).stream()
                .map(record -> new ExceptionView(
                        record.getId(),
                        record.getStatus().name(),
                        record.getEntityRef(),
                        record.getReason(),
                        record.getConfidence(),
                        record.getOrigin().name(),
                        record.getSourceRowIds()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<MatchView> matches(UUID batchId, Pageable pageable) {
        requireBatch(batchId);
        Map<Long, MerchantOrder> ordersById = byId(orderRepository.findByBatchIdOrderById(batchId), MerchantOrder::getId);
        Map<Long, SettlementBatch> settlementsById =
                byId(settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId), SettlementBatch::getId);
        Map<Long, BankEntry> bankById = byId(bankEntryRepository.findByBatchIdOrderById(batchId), BankEntry::getId);

        return matchRepository.findByBatchIdOrderById(batchId, pageable).map(match -> {
            MerchantOrder order = ordersById.get(match.getOrderRowId());
            SettlementBatch settlement = settlementsById.get(match.getSettlementBatchRowId());
            BankEntry bankEntry = bankById.get(match.getBankEntryRowId());
            return new MatchView(
                    match.getId(),
                    match.getMatchType(),
                    order == null ? null : order.getOrderId(),
                    settlement == null ? null : settlement.getUtr(),
                    match.getAmount(),
                    settlement == null ? null : settlement.getSettledOn(),
                    bankEntry == null ? null : bankEntry.getEntryDate(),
                    bankEntry == null ? null : bankEntry.getAmount());
        });
    }

    private ReconcileSummary buildSummary(UUID batchId,
                                          List<MerchantOrder> orders,
                                          List<SettlementBatch> settlements,
                                          List<BankEntry> bankEntries,
                                          List<MatchRecord> matches) {
        int matchedOrders = (int) matches.stream().filter(match -> match.getOrderRowId() != null).count();
        int bankMatches = (int) matches.stream().filter(match -> match.getBankEntryRowId() != null).count();

        Map<String, Integer> matchesByType = new TreeMap<>();
        matches.forEach(match -> matchesByType.merge(match.getMatchType(), 1, Integer::sum));

        Map<String, Integer> countsByStatus = new TreeMap<>();
        countsByStatus.put(ExceptionStatus.MATCHED.name(), matchedOrders);
        exceptionRepository.findByBatchIdOrderById(batchId)
                .forEach(record -> countsByStatus.merge(record.getStatus().name(), 1, Integer::sum));

        BigDecimal matchRate = orders.isEmpty()
                ? ZERO
                : BigDecimal.valueOf(matchedOrders).divide(BigDecimal.valueOf(orders.size()), 4, RoundingMode.HALF_UP);

        return new ReconcileSummary(batchId,
                orders.size(), matchedOrders, matchRate,
                settlements.size(), bankMatches,
                bankEntries.size(), bankMatches,
                matchesByType,
                countsByStatus,
                sum(orders, MerchantOrder::getAmount),
                sum(settlements, SettlementBatch::getAmount),
                sum(bankEntries, BankEntry::getAmount));
    }

    private AuditLog auditEntry(UUID batchId, ReconcileSummary summary) {
        AuditLog entry = new AuditLog();
        entry.setLoggedAt(LocalDateTime.now());
        entry.setBatchId(batchId);
        entry.setAction("RECONCILE");
        entry.setDetail("matched %d/%d orders to settlement lines and %d/%d settlements to bank credits across %d bank rows"
                .formatted(summary.matchedOrderCount(), summary.orderCount(),
                        summary.matchedSettlementBatchCount(), summary.settlementBatchCount(),
                        summary.bankEntryCount()));
        return entry;
    }

    private void requireBatch(UUID batchId) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }
    }

    private static <T> Map<Long, T> byId(List<T> rows, Function<T, Long> id) {
        Map<Long, T> byId = new HashMap<>();
        rows.forEach(row -> byId.put(id.apply(row), row));
        return byId;
    }

    private static <T> BigDecimal sum(List<T> rows, Function<T, BigDecimal> amount) {
        return rows.stream().map(amount).reduce(ZERO, BigDecimal::add);
    }
}
