package com.ledgerlens.service;

import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.entity.IngestBatch;
import com.ledgerlens.entity.IngestSource;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentMethod;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.entity.Refund;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.repository.RefundRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.repository.SettlementLineRepository;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Pulls a window of test-mode activity straight from Razorpay instead of from exported files.
 *
 * <p>Two things about this path are worth stating plainly rather than discovering later. Razorpay
 * knows nothing about the merchant's bank account, so no bank rows are created and a batch ingested
 * this way can be matched on the Razorpay side but not carried through to the bank until a statement
 * is supplied. And the API has no dispute feed here, so payments held behind a dispute are not
 * marked as held.
 *
 * <p>What it does add that the CSV path cannot: refunds arrive as real objects with their own
 * creation time and the payment they belong to, so the refunds table is populated properly.
 */
@Service
public class RazorpayIngestService {

    private static final Logger log = LoggerFactory.getLogger(RazorpayIngestService.class);

    /** Razorpay pages at 100 records; this caps a single pull so one call cannot run away. */
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final ZoneId MERCHANT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final IngestBatchRepository ingestBatchRepository;
    private final MerchantOrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final String keyId;
    private final String keySecret;

    public RazorpayIngestService(IngestBatchRepository ingestBatchRepository,
                                 MerchantOrderRepository orderRepository,
                                 PaymentRepository paymentRepository,
                                 RefundRepository refundRepository,
                                 SettlementBatchRepository settlementBatchRepository,
                                 SettlementLineRepository settlementLineRepository,
                                 @Value("${RAZORPAY_KEY_ID:}") String keyId,
                                 @Value("${RAZORPAY_KEY_SECRET:}") String keySecret) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.keyId = keyId;
        this.keySecret = keySecret;
    }

    public boolean configured() {
        return !keyId.isBlank() && !keySecret.isBlank();
    }

    public IngestResponse ingest(LocalDate from, LocalDate to) {
        if (!configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Razorpay ingest needs RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET; "
                            + "CSV ingest at POST /api/ingest/csv works without them");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "to must not be before from");
        }

        long fromEpoch = from.atStartOfDay(MERCHANT_ZONE).toEpochSecond();
        long toEpoch = to.plusDays(1).atStartOfDay(MERCHANT_ZONE).toEpochSecond() - 1;
        try {
            RazorpayClient client = new RazorpayClient(keyId, keySecret);
            return persist(
                    fetchAll(request -> client.payments.fetchAll(request), fromEpoch, toEpoch),
                    fetchAll(request -> client.refunds.fetchAll(request), fromEpoch, toEpoch),
                    fetchRecon(client, fromEpoch, toEpoch));
        } catch (RazorpayException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Razorpay rejected the request: " + e.getMessage(), e);
        }
    }

    /**
     * Turns raw Razorpay JSON into stored rows. Kept apart from the fetching so it can be exercised
     * against recorded payloads without a key or a network.
     */
    @Transactional
    public IngestResponse persist(List<JSONObject> payments, List<JSONObject> refunds, List<JSONObject> reconLines) {
        IngestBatch batch = new IngestBatch();
        batch.setId(UUID.randomUUID());
        batch.setSource(IngestSource.RAZORPAY);
        batch.setCreatedAt(LocalDateTime.now());
        ingestBatchRepository.save(batch);
        UUID batchId = batch.getId();

        List<MerchantOrder> orders = new ArrayList<>();
        List<Payment> storedPayments = new ArrayList<>();
        Map<String, String> orderIdByPaymentId = new LinkedHashMap<>();

        for (JSONObject json : payments) {
            String orderId = optString(json, "order_id");
            PaymentMethod method = methodOf(optString(json, "method"));
            PaymentStatus status = statusOf(optString(json, "status"));
            if (orderId == null || method == null || status == null) {
                log.debug("skipping payment {}: no order, or an unsupported method or status", optString(json, "id"));
                continue;
            }

            BigDecimal amount = rupees(json, "amount");
            LocalDateTime createdAt = timestamp(json);
            MerchantOrder order = new MerchantOrder();
            order.setBatchId(batchId);
            order.setOrderId(orderId);
            order.setOrderTs(createdAt);
            order.setAmount(amount);
            orders.add(order);

            BigDecimal fee = rupees(json, "fee");
            BigDecimal gst = rupees(json, "tax");
            Payment payment = new Payment();
            payment.setBatchId(batchId);
            payment.setPaymentId(optString(json, "id"));
            payment.setOrderId(orderId);
            payment.setMethod(method);
            payment.setAmount(amount);
            payment.setFee(fee);
            payment.setGst(gst);
            payment.setNetAmount(status == PaymentStatus.CAPTURED ? amount.subtract(fee).subtract(gst) : ZERO);
            payment.setStatus(status);
            payment.setCreatedAt(createdAt);
            storedPayments.add(payment);
            orderIdByPaymentId.put(payment.getPaymentId(), orderId);
        }
        orderRepository.saveAll(orders);
        paymentRepository.saveAll(storedPayments);

        List<Refund> storedRefunds = new ArrayList<>();
        for (JSONObject json : refunds) {
            String paymentId = optString(json, "payment_id");
            if (paymentId == null) {
                continue;
            }
            Refund refund = new Refund();
            refund.setBatchId(batchId);
            refund.setRefundId(optString(json, "id"));
            refund.setPaymentId(paymentId);
            refund.setOrderId(orderIdByPaymentId.get(paymentId));
            refund.setAmount(rupees(json, "amount"));
            refund.setCreatedAt(timestamp(json));
            storedRefunds.add(refund);
        }
        refundRepository.saveAll(storedRefunds);

        Map<String, SettlementBatch> settlementsByUtr = new LinkedHashMap<>();
        List<JSONObject> settledLines = new ArrayList<>();
        for (JSONObject json : reconLines) {
            String utr = optString(json, "utr");
            if (utr == null) {
                continue;
            }
            SettlementBatch settlement = settlementsByUtr.computeIfAbsent(utr, reference -> {
                SettlementBatch created = new SettlementBatch();
                created.setBatchId(batchId);
                created.setUtr(reference);
                created.setSettledOn(settledOn(json));
                created.setAmount(ZERO);
                return created;
            });
            settlement.setAmount(settlement.getAmount().add(netOf(json)));
            settledLines.add(json);
        }
        settlementBatchRepository.saveAll(settlementsByUtr.values());

        List<SettlementLine> lines = new ArrayList<>(settledLines.size());
        for (JSONObject json : settledLines) {
            boolean isRefund = "refund".equalsIgnoreCase(optString(json, "type"));
            BigDecimal gross = rupees(json, "amount");
            SettlementLine line = new SettlementLine();
            line.setBatchId(batchId);
            line.setSettlementBatchRowId(settlementsByUtr.get(optString(json, "utr")).getId());
            line.setLineType(isRefund ? SettlementLineType.REFUND : SettlementLineType.PAYMENT);
            line.setEntityId(optString(json, "entity_id"));
            line.setOrderId(optString(json, "order_id"));
            line.setAmount(isRefund ? gross.negate() : gross);
            lines.add(line);
        }
        settlementLineRepository.saveAll(lines);

        // No bank rows: Razorpay cannot see the merchant's bank account.
        return new IngestResponse(batchId, orders.size(), storedPayments.size(), 0,
                settlementsByUtr.size(), lines.size(), 0);
    }

    private List<JSONObject> fetchAll(PageFetcher fetcher, long fromEpoch, long toEpoch) throws RazorpayException {
        List<JSONObject> collected = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONObject request = new JSONObject()
                    .put("from", fromEpoch)
                    .put("to", toEpoch)
                    .put("count", PAGE_SIZE)
                    .put("skip", page * PAGE_SIZE);
            int before = collected.size();
            for (com.razorpay.Entity entity : fetcher.fetch(request)) {
                collected.add(entity.toJson());
            }
            if (collected.size() - before < PAGE_SIZE) {
                return collected;
            }
        }
        log.warn("stopped after {} pages; narrow the date range to pull the rest", MAX_PAGES);
        return collected;
    }

    private List<JSONObject> fetchRecon(RazorpayClient client, long fromEpoch, long toEpoch) throws RazorpayException {
        JSONObject request = new JSONObject()
                .put("year", LocalDate.now(MERCHANT_ZONE).getYear())
                .put("from", fromEpoch)
                .put("to", toEpoch)
                .put("count", PAGE_SIZE);
        List<JSONObject> lines = new ArrayList<>();
        for (com.razorpay.Entity entity : client.settlement.reports(request)) {
            lines.add(entity.toJson());
        }
        return lines;
    }

    @FunctionalInterface
    private interface PageFetcher {
        List<? extends com.razorpay.Entity> fetch(JSONObject request) throws RazorpayException;
    }

    // ---------- field readers, all tolerant of a payload that omits things ----------

    static BigDecimal rupees(JSONObject json, String field) {
        return json.has(field) && !json.isNull(field)
                ? BigDecimal.valueOf(json.getLong(field), 2)
                : ZERO;
    }

    /** A settlement line credits or debits; the net is what actually moved. */
    private static BigDecimal netOf(JSONObject json) {
        BigDecimal credit = rupees(json, "credit");
        BigDecimal debit = rupees(json, "debit");
        if (credit.signum() != 0 || debit.signum() != 0) {
            return credit.subtract(debit);
        }
        BigDecimal amount = rupees(json, "amount");
        return "refund".equalsIgnoreCase(optString(json, "type")) ? amount.negate() : amount;
    }

    private static LocalDate settledOn(JSONObject json) {
        return json.has("settled_at") && !json.isNull("settled_at")
                ? LocalDateTime.ofEpochSecond(json.getLong("settled_at"), 0,
                        MERCHANT_ZONE.getRules().getOffset(LocalDateTime.now())).toLocalDate()
                : LocalDate.now(MERCHANT_ZONE);
    }

    private static LocalDateTime timestamp(JSONObject json) {
        long epochSeconds = json.has("created_at") && !json.isNull("created_at") ? json.getLong("created_at") : 0L;
        return LocalDateTime.ofEpochSecond(epochSeconds, 0,
                MERCHANT_ZONE.getRules().getOffset(LocalDateTime.now()));
    }

    private static String optString(JSONObject json, String field) {
        if (!json.has(field) || json.isNull(field)) {
            return null;
        }
        String value = json.get(field).toString();
        return value.isBlank() ? null : value;
    }

    private static PaymentMethod methodOf(String method) {
        if (method == null) {
            return null;
        }
        return switch (method.toLowerCase(Locale.ROOT)) {
            case "upi" -> PaymentMethod.UPI;
            case "card" -> PaymentMethod.CARD;
            case "netbanking" -> PaymentMethod.NETBANKING;
            case "wallet" -> PaymentMethod.WALLET;
            default -> null;
        };
    }

    /** Only money that actually moved is worth storing, so authorised and created payments are skipped. */
    private static PaymentStatus statusOf(String status) {
        if (status == null) {
            return null;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "captured", "refunded" -> PaymentStatus.CAPTURED;
            case "failed" -> PaymentStatus.FAILED;
            default -> null;
        };
    }
}
