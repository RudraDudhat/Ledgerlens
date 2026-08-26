package com.ledgerlens.service;

import com.ledgerlens.dto.ForecastEntry;
import com.ledgerlens.entity.Dispute;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import com.ledgerlens.repository.DisputeRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import com.ledgerlens.rules.DisputeHolds;
import com.ledgerlens.rules.SettlementCalendar;
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
import java.util.TreeMap;
import java.util.UUID;

/**
 * The forward settlement calendar: money the batch says is still coming.
 *
 * <p>Two things land in the future. A payment captured too late in the window to have settled yet
 * arrives on its normal T+1 or T+2 cycle. A payment held behind a dispute arrives on its release
 * date, and only if the dispute was won — an open or lost dispute has no date, so that money is
 * deliberately absent from the calendar rather than promised on a guessed day.
 *
 * <p>Everything is measured from the last settlement the report covers, so the forecast is what the
 * batch itself implies rather than what the wall clock happens to say today.
 */
@Service
public class ForecastService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final IngestBatchRepository ingestBatchRepository;
    private final PaymentRepository paymentRepository;
    private final DisputeRepository disputeRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final SettlementBatchRepository settlementBatchRepository;

    public ForecastService(IngestBatchRepository ingestBatchRepository,
                           PaymentRepository paymentRepository,
                           DisputeRepository disputeRepository,
                           SettlementLineRepository settlementLineRepository,
                           SettlementBatchRepository settlementBatchRepository) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.settlementBatchRepository = settlementBatchRepository;
    }

    @Transactional(readOnly = true)
    public List<ForecastEntry> forecast(UUID batchId) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }

        List<SettlementBatch> settlements = settlementBatchRepository.findByBatchIdOrderBySettledOn(batchId);
        LocalDate asOf = settlements.stream()
                .map(SettlementBatch::getSettledOn)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(LocalDate.MIN);

        Set<String> alreadySettled = new HashSet<>();
        for (SettlementLine line : settlementLineRepository.findByBatchIdOrderById(batchId)) {
            if (line.getLineType() == SettlementLineType.PAYMENT) {
                alreadySettled.add(line.getEntityId());
            }
        }

        Map<String, Dispute> disputesByPayment = new HashMap<>();
        disputeRepository.findByBatchIdOrderById(batchId)
                .forEach(dispute -> disputesByPayment.put(dispute.getPaymentId(), dispute));

        Map<LocalDate, Pending> pendingByDate = new TreeMap<>();
        for (Payment payment : paymentRepository.findByBatchIdOrderById(batchId)) {
            if (payment.getStatus() != PaymentStatus.CAPTURED || alreadySettled.contains(payment.getPaymentId())) {
                continue;
            }

            Dispute dispute = disputesByPayment.get(payment.getPaymentId());
            LocalDate expectedOn;
            boolean fromHold;
            if (dispute != null) {
                expectedOn = DisputeHolds.expectedReleaseDate(dispute.getStatus(), dispute.getOpenedAt());
                fromHold = true;
            } else {
                expectedOn = SettlementCalendar.settlementDate(payment.getMethod(),
                        payment.getCreatedAt().toLocalDate());
                fromHold = false;
            }

            // No release date means nobody can say when, so it is left off the calendar entirely.
            if (expectedOn == null || !expectedOn.isAfter(asOf)) {
                continue;
            }
            pendingByDate.computeIfAbsent(expectedOn, date -> new Pending())
                    .add(payment.getMethod().name(), payment.getNetAmount(), fromHold);
        }

        List<ForecastEntry> forecast = new ArrayList<>(pendingByDate.size());
        pendingByDate.forEach((date, pending) -> forecast.add(new ForecastEntry(
                date, pending.total, Map.copyOf(pending.byMethod), pending.held)));
        return forecast;
    }

    private static final class Pending {
        private final Map<String, BigDecimal> byMethod = new TreeMap<>();
        private BigDecimal total = ZERO;
        private BigDecimal held = ZERO;

        private void add(String method, BigDecimal amount, boolean fromHold) {
            byMethod.merge(method, amount, BigDecimal::add);
            total = total.add(amount);
            if (fromHold) {
                held = held.add(amount);
            }
        }
    }
}
