package com.ledgerlens.service;

import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.Dispute;
import com.ledgerlens.entity.ExceptionOrigin;
import com.ledgerlens.entity.ExceptionRecord;
import com.ledgerlens.entity.ExceptionStatus;
import com.ledgerlens.entity.MatchRecord;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.DisputeRepository;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.repository.MatchRecordRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import com.ledgerlens.rules.DisputeHolds;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Explains, deterministically, everything the matcher could not pair up.
 *
 * <p>Confidence is not decoration. A status read straight out of a column is certain; a status
 * inferred from something being absent is not, because absence has more than one cause — a
 * settlement missing from the bank may simply be a day late. Anything no rule explains is recorded
 * as UNKNOWN at low confidence rather than being guessed at, which is what leaves work for the
 * classifier instead of hiding it.
 */
@Service
public class ExceptionDetectionService {

    private static final BigDecimal CERTAIN = new BigDecimal("1.000");
    private static final BigDecimal LIKELY = new BigDecimal("0.950");
    private static final BigDecimal PROBABLE = new BigDecimal("0.900");
    private static final BigDecimal INFERRED = new BigDecimal("0.850");
    private static final BigDecimal UNSURE = new BigDecimal("0.300");

    private final MerchantOrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final DisputeRepository disputeRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankEntryRepository bankEntryRepository;
    private final MatchRecordRepository matchRepository;
    private final ExceptionRecordRepository exceptionRepository;

    public ExceptionDetectionService(MerchantOrderRepository orderRepository,
                                     PaymentRepository paymentRepository,
                                     DisputeRepository disputeRepository,
                                     SettlementLineRepository settlementLineRepository,
                                     SettlementBatchRepository settlementBatchRepository,
                                     BankEntryRepository bankEntryRepository,
                                     MatchRecordRepository matchRepository,
                                     ExceptionRecordRepository exceptionRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.bankEntryRepository = bankEntryRepository;
        this.matchRepository = matchRepository;
        this.exceptionRepository = exceptionRepository;
    }

    @Transactional
    public List<ExceptionRecord> detect(UUID batchId) {
        exceptionRepository.deleteByBatchId(batchId);
        exceptionRepository.flush();

        List<MerchantOrder> orders = orderRepository.findByBatchIdOrderById(batchId);
        List<Payment> payments = paymentRepository.findByBatchIdOrderById(batchId);
        List<Dispute> disputes = disputeRepository.findByBatchIdOrderById(batchId);
        List<SettlementLine> lines = settlementLineRepository.findByBatchIdOrderById(batchId);
        List<SettlementBatch> settlements = settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId);
        List<BankEntry> bankEntries = bankEntryRepository.findByBatchIdOrderById(batchId);
        List<MatchRecord> matches = matchRepository.findByBatchIdOrderById(batchId);

        Set<String> knownOrderIds = new HashSet<>();
        orders.forEach(order -> knownOrderIds.add(order.getOrderId()));
        Map<Long, SettlementBatch> settlementsById = new HashMap<>();
        settlements.forEach(settlement -> settlementsById.put(settlement.getId(), settlement));
        Map<Long, BankEntry> bankById = new HashMap<>();
        bankEntries.forEach(entry -> bankById.put(entry.getId(), entry));

        Map<String, LocalDate> paymentCycleByOrder = new HashMap<>();
        for (SettlementLine line : lines) {
            if (line.getLineType() == SettlementLineType.PAYMENT && line.getOrderId() != null) {
                SettlementBatch settlement = settlementsById.get(line.getSettlementBatchRowId());
                if (settlement != null) {
                    paymentCycleByOrder.put(line.getOrderId(), settlement.getSettledOn());
                }
            }
        }

        Set<Long> matchedOrderRowIds = new HashSet<>();
        Set<Long> creditedSettlementIds = new HashSet<>();
        Set<Long> claimedBankIds = new HashSet<>();
        for (MatchRecord match : matches) {
            if (match.getOrderRowId() != null) {
                matchedOrderRowIds.add(match.getOrderRowId());
            }
            if (match.getBankEntryRowId() != null) {
                creditedSettlementIds.add(match.getSettlementBatchRowId());
                claimedBankIds.add(match.getBankEntryRowId());
            }
        }

        LocalDate asOf = settlements.stream()
                .map(SettlementBatch::getSettledOn)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.MIN);
        Map<String, Dispute> heldDisputesByPayment = new HashMap<>();
        for (Dispute dispute : disputes) {
            if (DisputeHolds.isHeldAsOf(dispute.getStatus(), dispute.getOpenedAt(), asOf)) {
                heldDisputesByPayment.put(dispute.getPaymentId(), dispute);
            }
        }

        List<ExceptionRecord> found = new ArrayList<>();
        Set<String> explainedOrderIds = new HashSet<>();

        for (Payment payment : payments) {
            if (payment.getStatus() == PaymentStatus.FAILED) {
                explainedOrderIds.add(payment.getOrderId());
                found.add(exception(batchId, ExceptionStatus.PAYMENT_FAILED, payment.getOrderId(),
                        "The gateway reports this payment as failed, so it never entered a settlement.",
                        CERTAIN, List.of(payment.getId())));
                continue;
            }
            Dispute dispute = heldDisputesByPayment.get(payment.getPaymentId());
            if (dispute != null) {
                explainedOrderIds.add(payment.getOrderId());
                LocalDate release = DisputeHolds.expectedReleaseDate(dispute.getStatus(), dispute.getOpenedAt());
                found.add(exception(batchId, ExceptionStatus.HELD_DISPUTE, payment.getOrderId(),
                        "A %s dispute opened on %s holds this payment out of settlement. %s"
                                .formatted(dispute.getStatus(), dispute.getOpenedAt().toLocalDate(),
                                        release == null
                                                ? "No release date is known until the dispute is resolved."
                                                : "It is expected to be released on " + release + "."),
                        CERTAIN, List.of(payment.getId(), dispute.getId())));
            }
        }

        for (SettlementLine line : lines) {
            if (line.getLineType() != SettlementLineType.REFUND || line.getOrderId() == null) {
                continue;
            }
            SettlementBatch settlement = settlementsById.get(line.getSettlementBatchRowId());
            LocalDate deductedOn = settlement == null ? null : settlement.getSettledOn();
            LocalDate paidOn = paymentCycleByOrder.get(line.getOrderId());

            if (paidOn != null && deductedOn != null && paidOn.isBefore(deductedOn)) {
                found.add(exception(batchId, ExceptionStatus.REFUND_PRIOR_CYCLE, line.getOrderId(),
                        "This refund was deducted from the %s cycle although the payment settled on %s, so that cycle is short by the refund."
                                .formatted(deductedOn, paidOn),
                        CERTAIN, List.of(line.getId())));
            } else if (!knownOrderIds.contains(line.getOrderId())) {
                found.add(exception(batchId, ExceptionStatus.REFUND_PRIOR_CYCLE, line.getOrderId(),
                        "This refund has no order in the batch, so it belongs to an order from before the window and reduces a cycle it was never part of.",
                        INFERRED, List.of(line.getId())));
            } else if (paidOn == null) {
                found.add(exception(batchId, ExceptionStatus.UNKNOWN, line.getOrderId(),
                        "The order exists but its payment never appears in the settlement report, so this refund cannot be tied to a cycle.",
                        UNSURE, List.of(line.getId())));
            }
        }

        for (SettlementBatch settlement : settlements) {
            if (!creditedSettlementIds.contains(settlement.getId())) {
                found.add(exception(batchId, ExceptionStatus.BANK_MISSING, settlement.getUtr(),
                        "Razorpay settled %s under this UTR but no bank credit carries it. It may be a day late rather than lost."
                                .formatted(settlement.getAmount()),
                        LIKELY, List.of(settlement.getId())));
            }
        }

        Map<String, SettlementBatch> settlementsByUtr = new HashMap<>();
        for (SettlementBatch settlement : settlements) {
            String utr = DeterministicMatcher.normalizeUtr(settlement.getUtr());
            if (utr != null) {
                settlementsByUtr.put(utr, settlement);
            }
        }
        for (BankEntry entry : bankEntries) {
            if (claimedBankIds.contains(entry.getId())) {
                continue;
            }
            String utr = DeterministicMatcher.utrOf(entry);
            SettlementBatch twin = utr == null ? null : settlementsByUtr.get(utr);
            if (twin != null && creditedSettlementIds.contains(twin.getId())) {
                found.add(exception(batchId, ExceptionStatus.BANK_DUPLICATE, twin.getUtr(),
                        "The bank posted this credit twice under one UTR, so %s was received once more than Razorpay settled."
                                .formatted(entry.getAmount()),
                        PROBABLE, List.of(entry.getId(), twin.getId())));
            } else {
                found.add(exception(batchId, ExceptionStatus.UNKNOWN,
                        utr == null ? "bank-row-" + entry.getId() : utr,
                        "A bank credit of %s on %s matches no settlement by reference, amount or date."
                                .formatted(entry.getAmount(), entry.getEntryDate()),
                        UNSURE, List.of(entry.getId())));
            }
        }

        for (MatchRecord match : matches) {
            if (match.getBankEntryRowId() == null) {
                continue;
            }
            BankEntry entry = bankById.get(match.getBankEntryRowId());
            SettlementBatch settlement = settlementsById.get(match.getSettlementBatchRowId());
            if (entry == null || settlement == null) {
                continue;
            }
            BigDecimal difference = entry.getAmount().subtract(settlement.getAmount());
            if (difference.signum() != 0) {
                found.add(exception(batchId, ExceptionStatus.AMOUNT_MISMATCH, settlement.getUtr(),
                        "The bank credited %s against a settlement of %s, a difference of %s."
                                .formatted(entry.getAmount(), settlement.getAmount(), difference),
                        CERTAIN, List.of(settlement.getId(), entry.getId())));
            }
        }

        for (MerchantOrder order : orders) {
            if (matchedOrderRowIds.contains(order.getId()) || explainedOrderIds.contains(order.getOrderId())) {
                continue;
            }
            found.add(exception(batchId, ExceptionStatus.UNKNOWN, order.getOrderId(),
                    "This order was captured and is not held, yet no settlement line carries it.",
                    UNSURE, List.of(order.getId())));
        }

        return exceptionRepository.saveAll(found);
    }

    private static ExceptionRecord exception(UUID batchId, ExceptionStatus status, String entityRef,
                                             String reason, BigDecimal confidence, List<Long> sourceRowIds) {
        ExceptionRecord record = new ExceptionRecord();
        record.setBatchId(batchId);
        record.setStatus(status);
        record.setEntityRef(entityRef);
        record.setReason(reason);
        record.setConfidence(confidence);
        record.setOrigin(ExceptionOrigin.RULE);
        record.setSourceRowIds(sourceRowIds);
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }
}
