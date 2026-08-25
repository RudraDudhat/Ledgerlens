package com.ledgerlens.dto;

import com.ledgerlens.entity.DisputeStatus;
import com.ledgerlens.entity.PaymentMethod;
import com.ledgerlens.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** The three input files plus the answer key, in memory, before they are written to disk. */
public record SyntheticDataset(
        List<OrderRow> orders,
        List<SettlementRow> settlementRows,
        List<BankRow> bankRows,
        AnswerKey answerKey) {

    /**
     * A row of the merchant's order export. Dispute columns are what makes a held payment visible to
     * the reconciler: the CSV ingest contract is only three files, so there is no separate disputes
     * export to read.
     */
    public record OrderRow(
            String orderId,
            LocalDateTime orderTs,
            BigDecimal amount,
            String paymentId,
            PaymentMethod method,
            PaymentStatus paymentStatus,
            DisputeStatus disputeStatus,
            LocalDateTime disputeOpenedAt) {
    }

    /** A line of the Razorpay settlement report. Refund lines carry negative amounts. */
    public record SettlementRow(
            String utr,
            LocalDate settledOn,
            String entityType,
            String entityId,
            String orderId,
            PaymentMethod method,
            BigDecimal grossAmount,
            BigDecimal fee,
            BigDecimal gst,
            BigDecimal netAmount) {
    }

    /** A credit line on the bank statement. One per settlement batch, unless the bank misbehaved. */
    public record BankRow(
            LocalDate entryDate,
            String description,
            String utr,
            BigDecimal creditAmount) {
    }
}
