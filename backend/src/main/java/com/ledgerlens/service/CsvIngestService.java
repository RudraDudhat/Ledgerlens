package com.ledgerlens.service;

import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.entity.Dispute;
import com.ledgerlens.repository.DisputeRepository;
import com.ledgerlens.entity.DisputeStatus;
import com.ledgerlens.entity.IngestBatch;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.entity.IngestSource;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.entity.Payment;
import com.ledgerlens.entity.PaymentMethod;
import com.ledgerlens.repository.PaymentRepository;
import com.ledgerlens.entity.PaymentStatus;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.repository.SettlementBatchRepository;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.repository.SettlementLineRepository;
import com.ledgerlens.entity.SettlementLineType;
import com.ledgerlens.rules.FeeSchedule;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads the three merchant files into one batch.
 *
 * <p>Orders are read first so that payments exist before the settlement report is applied: the
 * report carries the fees Razorpay actually charged, which overwrite the fees predicted from the fee
 * schedule at order time. Payments that never settled — failed, or held behind a dispute — keep the
 * predicted values, which is what lets the waterfall account for them.
 *
 * <p>The refunds table is not populated here. A settlement report states when a refund was deducted
 * but not when it was created or which payment it belongs to, so refunds are only carried as
 * settlement lines until the Razorpay API path can supply the full objects.
 */
@Service
public class CsvIngestService {

    private static final Set<String> ORDER_COLUMNS = Set.of("order_id", "order_ts", "amount", "payment_id",
            "method", "payment_status", "dispute_status", "dispute_opened_at");
    private static final Set<String> SETTLEMENT_COLUMNS = Set.of("utr", "settled_on", "entity_type", "entity_id",
            "order_id", "method", "gross_amount", "fee", "gst", "net_amount");
    private static final Set<String> BANK_COLUMNS = Set.of("entry_date", "description", "utr", "credit_amount");

    private final IngestBatchRepository ingestBatchRepository;
    private final MerchantOrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final DisputeRepository disputeRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementLineRepository settlementLineRepository;
    private final BankEntryRepository bankEntryRepository;

    public CsvIngestService(IngestBatchRepository ingestBatchRepository,
                            MerchantOrderRepository orderRepository,
                            PaymentRepository paymentRepository,
                            DisputeRepository disputeRepository,
                            SettlementBatchRepository settlementBatchRepository,
                            SettlementLineRepository settlementLineRepository,
                            BankEntryRepository bankEntryRepository) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.disputeRepository = disputeRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.settlementLineRepository = settlementLineRepository;
        this.bankEntryRepository = bankEntryRepository;
    }

    @Transactional
    public IngestResponse ingest(InputStream orders, InputStream settlement, InputStream bank) throws IOException {
        IngestBatch batch = new IngestBatch();
        batch.setId(UUID.randomUUID());
        batch.setSource(IngestSource.CSV);
        batch.setCreatedAt(LocalDateTime.now());
        ingestBatchRepository.save(batch);
        UUID batchId = batch.getId();

        OrderCounts orderCounts = ingestOrders(batchId, orders);
        SettlementCounts settlementCounts = ingestSettlement(batchId, settlement);
        int bankEntries = ingestBank(batchId, bank);

        return new IngestResponse(batchId, orderCounts.orders(), orderCounts.payments(), orderCounts.disputes(),
                settlementCounts.batches(), settlementCounts.lines(), bankEntries);
    }

    private OrderCounts ingestOrders(UUID batchId, InputStream in) throws IOException {
        List<MerchantOrder> orders = new ArrayList<>();
        List<Payment> payments = new ArrayList<>();
        List<Dispute> disputes = new ArrayList<>();

        for (CSVRecord row : read(in, ORDER_COLUMNS, "orders")) {
            String orderId = row.get("order_id");
            LocalDateTime orderTs = LocalDateTime.parse(row.get("order_ts"));
            BigDecimal amount = new BigDecimal(row.get("amount"));
            PaymentMethod method = PaymentMethod.valueOf(row.get("method"));
            PaymentStatus status = PaymentStatus.valueOf(row.get("payment_status"));

            MerchantOrder order = new MerchantOrder();
            order.setBatchId(batchId);
            order.setOrderId(orderId);
            order.setOrderTs(orderTs);
            order.setAmount(amount);
            orders.add(order);

            BigDecimal fee = status == PaymentStatus.CAPTURED ? FeeSchedule.fee(method, amount) : zero();
            BigDecimal gst = FeeSchedule.gst(fee);
            Payment payment = new Payment();
            payment.setBatchId(batchId);
            payment.setPaymentId(row.get("payment_id"));
            payment.setOrderId(orderId);
            payment.setMethod(method);
            payment.setAmount(amount);
            payment.setFee(fee);
            payment.setGst(gst);
            payment.setNetAmount(status == PaymentStatus.CAPTURED ? FeeSchedule.net(amount, fee, gst) : zero());
            payment.setStatus(status);
            payment.setCreatedAt(orderTs);
            payments.add(payment);

            String disputeStatus = row.get("dispute_status");
            if (!disputeStatus.isBlank()) {
                Dispute dispute = new Dispute();
                dispute.setBatchId(batchId);
                dispute.setDisputeId("disp-" + orderId);
                dispute.setPaymentId(payment.getPaymentId());
                dispute.setOpenedAt(LocalDateTime.parse(row.get("dispute_opened_at")));
                dispute.setStatus(DisputeStatus.valueOf(disputeStatus));
                disputes.add(dispute);
            }
        }

        orderRepository.saveAll(orders);
        paymentRepository.saveAll(payments);
        disputeRepository.saveAll(disputes);
        return new OrderCounts(orders.size(), payments.size(), disputes.size());
    }

    private SettlementCounts ingestSettlement(UUID batchId, InputStream in) throws IOException {
        List<CSVRecord> rows = read(in, SETTLEMENT_COLUMNS, "settlement");

        Map<String, SettlementBatch> batchesByUtr = new LinkedHashMap<>();
        for (CSVRecord row : rows) {
            SettlementBatch settlementBatch = batchesByUtr.computeIfAbsent(row.get("utr"), utr -> {
                SettlementBatch created = new SettlementBatch();
                created.setBatchId(batchId);
                created.setUtr(utr);
                created.setSettledOn(LocalDate.parse(row.get("settled_on")));
                created.setAmount(zero());
                return created;
            });
            settlementBatch.setAmount(settlementBatch.getAmount().add(new BigDecimal(row.get("net_amount"))));
        }
        settlementBatchRepository.saveAll(batchesByUtr.values());

        Map<String, Payment> paymentsById = new LinkedHashMap<>();
        paymentRepository.findByBatchIdOrderById(batchId).forEach(p -> paymentsById.put(p.getPaymentId(), p));

        List<SettlementLine> lines = new ArrayList<>(rows.size());
        List<Payment> repriced = new ArrayList<>();
        for (CSVRecord row : rows) {
            boolean isRefund = "refund".equalsIgnoreCase(row.get("entity_type"));
            SettlementLine line = new SettlementLine();
            line.setBatchId(batchId);
            line.setSettlementBatchRowId(batchesByUtr.get(row.get("utr")).getId());
            line.setLineType(isRefund ? SettlementLineType.REFUND : SettlementLineType.PAYMENT);
            line.setEntityId(row.get("entity_id"));
            line.setOrderId(emptyToNull(row.get("order_id")));
            line.setAmount(new BigDecimal(row.get("gross_amount")));
            lines.add(line);

            if (!isRefund) {
                Payment payment = paymentsById.get(row.get("entity_id"));
                if (payment != null) {
                    payment.setFee(new BigDecimal(row.get("fee")));
                    payment.setGst(new BigDecimal(row.get("gst")));
                    payment.setNetAmount(new BigDecimal(row.get("net_amount")));
                    repriced.add(payment);
                }
            }
        }
        settlementLineRepository.saveAll(lines);
        paymentRepository.saveAll(repriced);
        return new SettlementCounts(batchesByUtr.size(), lines.size());
    }

    private int ingestBank(UUID batchId, InputStream in) throws IOException {
        List<BankEntry> entries = new ArrayList<>();
        for (CSVRecord row : read(in, BANK_COLUMNS, "bank")) {
            BankEntry entry = new BankEntry();
            entry.setBatchId(batchId);
            entry.setEntryDate(LocalDate.parse(row.get("entry_date")));
            entry.setDescription(emptyToNull(row.get("description")));
            entry.setUtr(emptyToNull(row.get("utr")));
            entry.setAmount(new BigDecimal(row.get("credit_amount")));
            entries.add(entry);
        }
        bankEntryRepository.saveAll(entries);
        return entries.size();
    }

    private List<CSVRecord> read(InputStream in, Set<String> requiredColumns, String fileLabel) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .build();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {
            Set<String> present = parser.getHeaderMap().keySet();
            if (!present.containsAll(requiredColumns)) {
                List<String> missing = requiredColumns.stream().filter(column -> !present.contains(column)).sorted().toList();
                throw new IllegalArgumentException(fileLabel + " file is missing columns " + missing);
            }
            return parser.getRecords();
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static BigDecimal zero() {
        return new BigDecimal("0.00");
    }

    private record OrderCounts(int orders, int payments, int disputes) {
    }

    private record SettlementCounts(int batches, int lines) {
    }
}
