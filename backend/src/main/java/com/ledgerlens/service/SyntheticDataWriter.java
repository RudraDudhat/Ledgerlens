package com.ledgerlens.service;

import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ledgerlens.dto.SyntheticDataset;
import com.ledgerlens.dto.SyntheticDataset.BankRow;
import com.ledgerlens.dto.SyntheticDataset.OrderRow;
import com.ledgerlens.dto.SyntheticDataset.SettlementRow;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Writes the four batch files. Everything is emitted in a fixed order with LF line endings so that
 * regenerating with the same seed produces byte-identical files.
 */
public class SyntheticDataWriter {

    public static final String ORDERS_FILE = "orders.csv";
    public static final String SETTLEMENT_FILE = "razorpay_settlement.csv";
    public static final String BANK_FILE = "bank_statement.csv";
    public static final String ANSWER_KEY_FILE = "answer_key.json";

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Configured the same way wherever the answer key is read, so round-tripping is exact. */
    public static ObjectMapper answerKeyMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .build();
    }

    public void write(Path outDir, SyntheticDataset dataset) throws IOException {
        Files.createDirectories(outDir);
        writeOrders(outDir.resolve(ORDERS_FILE), dataset);
        writeSettlement(outDir.resolve(SETTLEMENT_FILE), dataset);
        writeBank(outDir.resolve(BANK_FILE), dataset);
        writeAnswerKey(outDir.resolve(ANSWER_KEY_FILE), dataset);
    }

    private void writeOrders(Path path, SyntheticDataset dataset) throws IOException {
        try (CSVPrinter csv = printer(path, "order_id", "order_ts", "amount", "payment_id", "method",
                "payment_status", "dispute_status", "dispute_opened_at")) {
            for (OrderRow order : dataset.orders()) {
                csv.printRecord(
                        order.orderId(),
                        order.orderTs().format(TIMESTAMP),
                        order.amount().toPlainString(),
                        order.paymentId(),
                        order.method(),
                        order.paymentStatus(),
                        order.disputeStatus() == null ? "" : order.disputeStatus(),
                        order.disputeOpenedAt() == null ? "" : order.disputeOpenedAt().format(TIMESTAMP));
            }
        }
    }

    private void writeSettlement(Path path, SyntheticDataset dataset) throws IOException {
        try (CSVPrinter csv = printer(path, "utr", "settled_on", "entity_type", "entity_id", "order_id",
                "method", "gross_amount", "fee", "gst", "net_amount")) {
            for (SettlementRow row : dataset.settlementRows()) {
                csv.printRecord(
                        row.utr(),
                        row.settledOn(),
                        row.entityType(),
                        row.entityId(),
                        row.orderId(),
                        row.method(),
                        row.grossAmount().toPlainString(),
                        row.fee().toPlainString(),
                        row.gst().toPlainString(),
                        row.netAmount().toPlainString());
            }
        }
    }

    private void writeBank(Path path, SyntheticDataset dataset) throws IOException {
        try (CSVPrinter csv = printer(path, "entry_date", "description", "utr", "credit_amount")) {
            for (BankRow row : dataset.bankRows()) {
                csv.printRecord(row.entryDate(), row.description(), row.utr(), row.creditAmount().toPlainString());
            }
        }
    }

    private void writeAnswerKey(Path path, SyntheticDataset dataset) throws IOException {
        DefaultPrettyPrinter prettyPrinter = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        prettyPrinter.indentObjectsWith(indenter);
        prettyPrinter.indentArraysWith(indenter);
        String json = answerKeyMapper().writer(prettyPrinter).writeValueAsString(dataset.answerKey());
        Files.writeString(path, json + "\n", StandardCharsets.UTF_8);
    }

    private CSVPrinter printer(Path path, String... headers) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(headers)
                .setRecordSeparator("\n")
                .build();
        return new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), format);
    }
}
