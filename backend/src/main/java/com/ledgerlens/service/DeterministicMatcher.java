package com.ledgerlens.service;

import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.MatchRecord;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.entity.SettlementLine;
import com.ledgerlens.entity.SettlementLineType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches in two passes and never guesses.
 *
 * <p>The first pass pairs an order with its settlement payment line on an exact order id and an
 * exact gross amount. The second pairs a settlement batch with the bank credit that carried it,
 * first on the UTR — normalised, and recovered from the narration when the statement has no UTR
 * column — and then, for batches still unpaired, on an exact amount landing within a few days of the
 * settlement date. Every pairing is one to one, so a settlement credited twice leaves the second
 * bank row unmatched rather than quietly absorbing it.
 *
 * <p>This class holds no state and touches no database, so it can be exercised directly.
 */
@Component
public class DeterministicMatcher {

    public static final String ORDER_ID_AMOUNT_EXACT = "ORDER_ID_AMOUNT_EXACT";
    public static final String UTR_EXACT = "UTR_EXACT";
    public static final String AMOUNT_DATE_WINDOW = "AMOUNT_DATE_WINDOW";

    static final int BANK_DATE_WINDOW_DAYS = 2;

    /** At least eight letters or digits, with at least one digit, so bank words are not mistaken for a UTR. */
    private static final Pattern UTR_TOKEN = Pattern.compile("(?=[A-Z0-9]*[0-9])[A-Z0-9]{8,}");

    public List<MatchRecord> match(UUID batchId,
                                   List<MerchantOrder> orders,
                                   List<SettlementLine> settlementLines,
                                   List<SettlementBatch> settlementBatches,
                                   List<BankEntry> bankEntries) {
        List<MatchRecord> matches = new ArrayList<>();
        matches.addAll(matchOrdersToSettlementLines(batchId, orders, settlementLines));
        matches.addAll(matchSettlementsToBank(batchId, settlementBatches, bankEntries));
        return matches;
    }

    private List<MatchRecord> matchOrdersToSettlementLines(UUID batchId,
                                                           List<MerchantOrder> orders,
                                                           List<SettlementLine> settlementLines) {
        Map<String, List<SettlementLine>> paymentLinesByOrder = new HashMap<>();
        for (SettlementLine line : settlementLines) {
            if (line.getLineType() == SettlementLineType.PAYMENT && line.getOrderId() != null) {
                paymentLinesByOrder.computeIfAbsent(line.getOrderId(), key -> new ArrayList<>()).add(line);
            }
        }

        Set<Long> claimedLines = new HashSet<>();
        List<MatchRecord> matches = new ArrayList<>();
        for (MerchantOrder order : orders) {
            for (SettlementLine line : paymentLinesByOrder.getOrDefault(order.getOrderId(), List.of())) {
                if (claimedLines.contains(line.getId()) || line.getAmount().compareTo(order.getAmount()) != 0) {
                    continue;
                }
                claimedLines.add(line.getId());
                matches.add(match(batchId, ORDER_ID_AMOUNT_EXACT, order.getId(), line.getId(),
                        line.getSettlementBatchRowId(), null, order.getAmount()));
                break;
            }
        }
        return matches;
    }

    private List<MatchRecord> matchSettlementsToBank(UUID batchId,
                                                     List<SettlementBatch> settlementBatches,
                                                     List<BankEntry> bankEntries) {
        Map<String, List<BankEntry>> entriesByUtr = new HashMap<>();
        for (BankEntry entry : bankEntries) {
            String utr = utrOf(entry);
            if (utr != null) {
                entriesByUtr.computeIfAbsent(utr, key -> new ArrayList<>()).add(entry);
            }
        }

        Set<Long> claimedEntries = new HashSet<>();
        List<MatchRecord> matches = new ArrayList<>();
        List<SettlementBatch> withoutUtrMatch = new ArrayList<>();

        for (SettlementBatch settlement : settlementBatches) {
            BankEntry entry = firstUnclaimed(entriesByUtr.get(normalizeUtr(settlement.getUtr())), claimedEntries);
            if (entry == null) {
                withoutUtrMatch.add(settlement);
                continue;
            }
            claimedEntries.add(entry.getId());
            matches.add(match(batchId, UTR_EXACT, null, null, settlement.getId(), entry.getId(), settlement.getAmount()));
        }

        for (SettlementBatch settlement : withoutUtrMatch) {
            for (BankEntry entry : bankEntries) {
                if (claimedEntries.contains(entry.getId())
                        || entry.getAmount().compareTo(settlement.getAmount()) != 0
                        || daysApart(settlement, entry) > BANK_DATE_WINDOW_DAYS) {
                    continue;
                }
                claimedEntries.add(entry.getId());
                matches.add(match(batchId, AMOUNT_DATE_WINDOW, null, null,
                        settlement.getId(), entry.getId(), settlement.getAmount()));
                break;
            }
        }
        return matches;
    }

    private static long daysApart(SettlementBatch settlement, BankEntry entry) {
        return Math.abs(ChronoUnit.DAYS.between(settlement.getSettledOn(), entry.getEntryDate()));
    }

    private static BankEntry firstUnclaimed(List<BankEntry> candidates, Set<Long> claimed) {
        if (candidates == null) {
            return null;
        }
        return candidates.stream().filter(entry -> !claimed.contains(entry.getId())).findFirst().orElse(null);
    }

    /** The statement's own UTR column when it has one, otherwise whatever the narration carries. */
    static String utrOf(BankEntry entry) {
        String fromColumn = normalizeUtr(entry.getUtr());
        return fromColumn != null ? fromColumn : normalizeUtr(extractUtr(entry.getDescription()));
    }

    static String normalizeUtr(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    static String extractUtr(String description) {
        if (description == null) {
            return null;
        }
        Matcher token = UTR_TOKEN.matcher(description.toUpperCase(Locale.ROOT));
        return token.find() ? token.group() : null;
    }

    private static MatchRecord match(UUID batchId, String matchType, Long orderRowId, Long settlementLineRowId,
                                     Long settlementBatchRowId, Long bankEntryRowId, BigDecimal amount) {
        MatchRecord match = new MatchRecord();
        match.setBatchId(batchId);
        match.setMatchType(matchType);
        match.setOrderRowId(orderRowId);
        match.setSettlementLineRowId(settlementLineRowId);
        match.setSettlementBatchRowId(settlementBatchRowId);
        match.setBankEntryRowId(bankEntryRowId);
        match.setAmount(amount);
        match.setCreatedAt(LocalDateTime.now());
        return match;
    }
}
