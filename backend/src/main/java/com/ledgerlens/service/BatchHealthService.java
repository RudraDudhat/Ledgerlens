package com.ledgerlens.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerlens.dto.BatchMetrics;
import com.ledgerlens.dto.HealthHistoryPoint;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.BatchHealth;
import com.ledgerlens.entity.MatchRecord;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentMethod;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.BatchHealthRepository;
import com.ledgerlens.repository.DisputeRepository;
import com.ledgerlens.repository.MatchRecordRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import com.ledgerlens.rules.SettlementCalendar;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Reduces a batch to a handful of ratios that survive comparison across batches of different sizes.
 *
 * <p>Rates are deliberately per-batch shares rather than counts: a week with twice the orders would
 * otherwise look like a week with twice the failures. The hour buckets exist because an outage
 * confined to a few night hours barely disturbs the overall failure rate and would go unnoticed.
 */
@Service
public class BatchHealthService {

    private static final int RATE_SCALE = 6;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.UNNECESSARY);

    private final MerchantOrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final DisputeRepository disputeRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankEntryRepository bankEntryRepository;
    private final MatchRecordRepository matchRepository;
    private final BatchHealthRepository batchHealthRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public BatchHealthService(MerchantOrderRepository orderRepository,
                              PaymentRepository paymentRepository,
                              DisputeRepository disputeRepository,
                              SettlementLineRepository settlementLineRepository,
                              SettlementBatchRepository settlementBatchRepository,
                              BankEntryRepository bankEntryRepository,
                              MatchRecordRepository matchRepository,
                              BatchHealthRepository batchHealthRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.bankEntryRepository = bankEntryRepository;
        this.matchRepository = matchRepository;
        this.batchHealthRepository = batchHealthRepository;
    }

    @Transactional
    public BatchMetrics computeAndStore(UUID batchId) {
        BatchMetrics metrics = compute(batchId);
        batchHealthRepository.deleteByBatchId(batchId);
        batchHealthRepository.flush();

        BatchHealth stored = new BatchHealth();
        stored.setBatchId(batchId);
        stored.setMetrics(write(metrics));
        stored.setComputedAt(LocalDateTime.now());
        batchHealthRepository.save(stored);
        return metrics;
    }

    @Transactional(readOnly = true)
    public BatchMetrics compute(UUID batchId) {
        List<Payment> payments = paymentRepository.findByBatchIdOrderById(batchId);
        long orderCount = orderRepository.countByBatchId(batchId);
        long disputeCount = disputeRepository.countByBatchId(batchId);

        BigDecimal sales = ZERO;
        BigDecimal fees = ZERO;
        int failed = 0;
        Map<PaymentMethod, int[]> byMethod = new LinkedHashMap<>();
        Map<Integer, int[]> byHour = new TreeMap<>();

        for (Payment payment : payments) {
            sales = sales.add(payment.getAmount());
            fees = fees.add(payment.getFee());
            boolean isFailed = payment.getStatus() == PaymentStatus.FAILED;
            if (isFailed) {
                failed++;
            }
            // index 0 counts failures, index 1 counts attempts
            int[] method = byMethod.computeIfAbsent(payment.getMethod(), key -> new int[2]);
            int[] hour = byHour.computeIfAbsent(payment.getCreatedAt().getHour(), key -> new int[2]);
            method[1]++;
            hour[1]++;
            if (isFailed) {
                method[0]++;
                hour[0]++;
            }
        }

        Map<String, BigDecimal> failureByMethod = new LinkedHashMap<>();
        byMethod.forEach((method, counts) -> failureByMethod.put(method.name(), ratio(counts[0], counts[1])));

        // Every hour is reported, so an empty hour reads as zero rather than as a gap in the chart.
        Map<String, BigDecimal> failureByHour = new LinkedHashMap<>();
        for (int hour = 0; hour < 24; hour++) {
            int[] counts = byHour.getOrDefault(hour, new int[2]);
            failureByHour.put(String.valueOf(hour), ratio(counts[0], counts[1]));
        }

        Map<String, BigDecimal> delayByMethod = settlementDelays(batchId, payments);
        BigDecimal averageDelay = delayByMethod.isEmpty()
                ? ZERO
                : delayByMethod.values().stream().reduce(ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(delayByMethod.size()), RATE_SCALE, RoundingMode.HALF_UP);

        long matchedOrders = matchRepository.findByBatchIdOrderById(batchId).stream()
                .filter(match -> match.getOrderRowId() != null)
                .count();

        return new BatchMetrics(
                divide(fees, sales),
                ratio(failed, payments.size()),
                failureByMethod,
                failureByHour,
                ratio((int) disputeCount, payments.size()),
                ratio((int) matchedOrders, (int) orderCount),
                delayByMethod,
                averageDelay,
                (int) orderCount);
    }

    /**
     * Business days from the payment to the bank credit that carried it. Only payments whose
     * settlement actually reached the bank can be measured, so a batch the bank never credited
     * reports no delay rather than a fabricated one.
     */
    private Map<String, BigDecimal> settlementDelays(UUID batchId, List<Payment> payments) {
        Map<Long, SettlementBatch> settlementsById = new HashMap<>();
        settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId)
                .forEach(settlement -> settlementsById.put(settlement.getId(), settlement));
        Map<Long, BankEntry> bankById = new HashMap<>();
        bankEntryRepository.findByBatchIdOrderById(batchId).forEach(entry -> bankById.put(entry.getId(), entry));

        Map<Long, LocalDate> creditedOnBySettlement = new HashMap<>();
        for (MatchRecord match : matchRepository.findByBatchIdOrderById(batchId)) {
            if (match.getBankEntryRowId() == null || match.getSettlementBatchRowId() == null) {
                continue;
            }
            BankEntry entry = bankById.get(match.getBankEntryRowId());
            if (entry != null) {
                creditedOnBySettlement.put(match.getSettlementBatchRowId(), entry.getEntryDate());
            }
        }

        Map<String, LocalDate> creditedOnByPayment = new HashMap<>();
        for (SettlementLine line : settlementLineRepository.findByBatchIdOrderById(batchId)) {
            if (line.getLineType() != SettlementLineType.PAYMENT) {
                continue;
            }
            LocalDate creditedOn = creditedOnBySettlement.get(line.getSettlementBatchRowId());
            if (creditedOn != null) {
                creditedOnByPayment.put(line.getEntityId(), creditedOn);
            }
        }

        Map<PaymentMethod, List<Integer>> delays = new LinkedHashMap<>();
        for (Payment payment : payments) {
            LocalDate creditedOn = creditedOnByPayment.get(payment.getPaymentId());
            if (creditedOn == null) {
                continue;
            }
            int days = businessDaysBetween(payment.getCreatedAt().toLocalDate(), creditedOn);
            delays.computeIfAbsent(payment.getMethod(), key -> new ArrayList<>()).add(days);
        }

        Map<String, BigDecimal> average = new LinkedHashMap<>();
        delays.forEach((method, values) -> {
            int total = values.stream().mapToInt(Integer::intValue).sum();
            average.put(method.name(),
                    BigDecimal.valueOf(total).divide(BigDecimal.valueOf(values.size()), RATE_SCALE, RoundingMode.HALF_UP));
        });
        return average;
    }

    public static int businessDaysBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) {
            return 0;
        }
        int days = 0;
        LocalDate cursor = from;
        while (cursor.isBefore(to)) {
            cursor = cursor.plusDays(1);
            if (SettlementCalendar.isBusinessDay(cursor)) {
                days++;
            }
        }
        return days;
    }

    @Transactional(readOnly = true)
    public List<HealthHistoryPoint> history(int limit) {
        List<BatchHealth> all = batchHealthRepository.findAllByOrderByComputedAtAsc();
        List<BatchHealth> tail = all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
        List<HealthHistoryPoint> points = new ArrayList<>(tail.size());
        tail.forEach(row -> points.add(new HealthHistoryPoint(row.getBatchId(), row.getComputedAt(), read(row.getMetrics()))));
        return points;
    }

    /** Every batch stored before this one, oldest first — the material a baseline is made from. */
    @Transactional(readOnly = true)
    public List<BatchMetrics> priorMetrics(UUID batchId) {
        List<BatchMetrics> prior = new ArrayList<>();
        for (BatchHealth row : batchHealthRepository.findAllByOrderByComputedAtAsc()) {
            if (row.getBatchId().equals(batchId)) {
                break;
            }
            prior.add(read(row.getMetrics()));
        }
        return prior;
    }

    private static BigDecimal ratio(int part, int whole) {
        return whole == 0
                ? ZERO
                : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal divide(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? ZERO : part.divide(whole, RATE_SCALE, RoundingMode.HALF_UP);
    }

    public String write(BatchMetrics metrics) {
        try {
            return mapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not store batch health", e);
        }
    }

    public BatchMetrics read(String json) {
        try {
            return mapper.readValue(json, BatchMetrics.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not read batch health", e);
        }
    }
}
