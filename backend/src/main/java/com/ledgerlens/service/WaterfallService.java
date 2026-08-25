package com.ledgerlens.service;

import com.ledgerlens.dto.WaterfallStep;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.Dispute;
import com.ledgerlens.entity.MatchRecord;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.DisputeRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.repository.MatchRecordRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import com.ledgerlens.rules.DisputeHolds;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Explains every rupee between what was sold and what reached the bank.
 *
 * <p>Nothing here is estimated. Each step is summed from stored rows, and the one figure that could
 * hide a mistake — the difference between what the fee, hold and refund rules predict should have
 * settled and what the settlement report actually totals — is emitted as its own step whenever it is
 * not zero, rather than being absorbed into a neighbouring line.
 */
@Service
public class WaterfallService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    public static final String GROSS_SALES = "Gross sales";
    public static final String FAILED_PAYMENTS = "Failed payments";
    public static final String FEES = "Razorpay fees";
    public static final String GST = "GST on fees";
    public static final String HELD = "Held for disputes";
    public static final String REFUNDS = "Refunds";
    public static final String UNEXPLAINED = "Unexplained settlement difference";
    public static final String NOT_CREDITED = "Settlements not credited by bank";
    public static final String UNMATCHED_CREDITS = "Unmatched bank credits";
    public static final String AMOUNT_DIFFERENCES = "Bank amount differences";

    private final IngestBatchRepository ingestBatchRepository;
    private final MerchantOrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final DisputeRepository disputeRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankEntryRepository bankEntryRepository;
    private final MatchRecordRepository matchRepository;

    public WaterfallService(IngestBatchRepository ingestBatchRepository,
                            MerchantOrderRepository orderRepository,
                            PaymentRepository paymentRepository,
                            DisputeRepository disputeRepository,
                            SettlementLineRepository settlementLineRepository,
                            SettlementBatchRepository settlementBatchRepository,
                            BankEntryRepository bankEntryRepository,
                            MatchRecordRepository matchRepository) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.bankEntryRepository = bankEntryRepository;
        this.matchRepository = matchRepository;
    }

    @Transactional(readOnly = true)
    public List<WaterfallStep> waterfall(UUID batchId) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }

        List<MerchantOrder> orders = orderRepository.findByBatchIdOrderById(batchId);
        List<Payment> payments = paymentRepository.findByBatchIdOrderById(batchId);
        List<SettlementLine> lines = settlementLineRepository.findByBatchIdOrderById(batchId);
        List<SettlementBatch> settlements = settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId);
        List<BankEntry> bankEntries = bankEntryRepository.findByBatchIdOrderById(batchId);
        List<MatchRecord> matches = matchRepository.findByBatchIdOrderById(batchId);
        Set<String> heldPaymentIds = heldPaymentIds(disputeRepository.findByBatchIdOrderById(batchId), settlements);

        BigDecimal grossSales = ZERO;
        List<Long> orderIds = new ArrayList<>(orders.size());
        for (MerchantOrder order : orders) {
            grossSales = grossSales.add(order.getAmount());
            orderIds.add(order.getId());
        }

        BigDecimal failed = ZERO;
        BigDecimal fees = ZERO;
        BigDecimal gst = ZERO;
        BigDecimal held = ZERO;
        List<Long> failedIds = new ArrayList<>();
        List<Long> capturedIds = new ArrayList<>();
        List<Long> heldIds = new ArrayList<>();
        for (Payment payment : payments) {
            if (payment.getStatus() == PaymentStatus.FAILED) {
                failed = failed.add(payment.getAmount());
                failedIds.add(payment.getId());
                continue;
            }
            fees = fees.add(payment.getFee());
            gst = gst.add(payment.getGst());
            capturedIds.add(payment.getId());
            if (heldPaymentIds.contains(payment.getPaymentId())) {
                held = held.add(payment.getNetAmount());
                heldIds.add(payment.getId());
            }
        }

        BigDecimal refunds = ZERO;
        List<Long> refundLineIds = new ArrayList<>();
        for (SettlementLine line : lines) {
            if (line.getLineType() == SettlementLineType.REFUND) {
                refunds = refunds.add(line.getAmount().negate());
                refundLineIds.add(line.getId());
            }
        }

        Set<Long> creditedSettlementIds = new HashSet<>();
        Set<Long> claimedBankIds = new HashSet<>();
        for (MatchRecord match : matches) {
            if (match.getBankEntryRowId() != null) {
                creditedSettlementIds.add(match.getSettlementBatchRowId());
                claimedBankIds.add(match.getBankEntryRowId());
            }
        }

        BigDecimal settled = ZERO;
        BigDecimal notCredited = ZERO;
        List<Long> notCreditedIds = new ArrayList<>();
        for (SettlementBatch settlement : settlements) {
            settled = settled.add(settlement.getAmount());
            if (!creditedSettlementIds.contains(settlement.getId())) {
                notCredited = notCredited.add(settlement.getAmount());
                notCreditedIds.add(settlement.getId());
            }
        }

        BigDecimal unmatchedCredits = ZERO;
        List<Long> unmatchedCreditIds = new ArrayList<>();
        for (BankEntry entry : bankEntries) {
            if (!claimedBankIds.contains(entry.getId())) {
                unmatchedCredits = unmatchedCredits.add(entry.getAmount());
                unmatchedCreditIds.add(entry.getId());
            }
        }

        BigDecimal amountDifferences = ZERO;
        List<Long> differingBankIds = new ArrayList<>();
        Map<Long, SettlementBatch> settlementsById = new HashMap<>();
        settlements.forEach(settlement -> settlementsById.put(settlement.getId(), settlement));
        Map<Long, BankEntry> bankById = new HashMap<>();
        bankEntries.forEach(entry -> bankById.put(entry.getId(), entry));
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
                amountDifferences = amountDifferences.add(difference);
                differingBankIds.add(entry.getId());
            }
        }

        BigDecimal predictedSettled = grossSales.subtract(failed).subtract(fees)
                .subtract(gst).subtract(held).subtract(refunds);
        BigDecimal unexplained = settled.subtract(predictedSettled);

        List<WaterfallStep> steps = new ArrayList<>();
        steps.add(new WaterfallStep(GROSS_SALES, grossSales, orderIds));
        steps.add(new WaterfallStep(FAILED_PAYMENTS, failed.negate(), failedIds));
        steps.add(new WaterfallStep(FEES, fees.negate(), capturedIds));
        steps.add(new WaterfallStep(GST, gst.negate(), capturedIds));
        steps.add(new WaterfallStep(HELD, held.negate(), heldIds));
        steps.add(new WaterfallStep(REFUNDS, refunds.negate(), refundLineIds));
        if (unexplained.signum() != 0) {
            steps.add(new WaterfallStep(UNEXPLAINED, unexplained, List.of()));
        }
        steps.add(new WaterfallStep(NOT_CREDITED, notCredited.negate(), notCreditedIds));
        steps.add(new WaterfallStep(UNMATCHED_CREDITS, unmatchedCredits, unmatchedCreditIds));
        steps.add(new WaterfallStep(AMOUNT_DIFFERENCES, amountDifferences, differingBankIds));
        return steps;
    }

    /** A dispute keeps its payment out of settlement until the batch's last settlement date passes. */
    private Set<String> heldPaymentIds(List<Dispute> disputes, List<SettlementBatch> settlements) {
        LocalDate asOf = settlements.stream()
                .map(SettlementBatch::getSettledOn)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.MIN);
        Set<String> held = new HashSet<>();
        for (Dispute dispute : disputes) {
            if (DisputeHolds.isHeldAsOf(dispute.getStatus(), dispute.getOpenedAt(), asOf)) {
                held.add(dispute.getPaymentId());
            }
        }
        return held;
    }
}
