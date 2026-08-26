package com.ledgerlens.service;

import com.ledgerlens.dto.AskResponse;
import com.ledgerlens.entity.BankEntry;
import com.ledgerlens.entity.ExceptionRecord;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.entity.SettlementBatch;
import com.ledgerlens.repository.BankEntryRepository;
import com.ledgerlens.repository.ExceptionRecordRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.ledgerlens.repository.SettlementBatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers questions about one batch from rows it actually retrieved.
 *
 * <p>Retrieval is by query, not by similarity: whatever order ids, dates and amounts the question
 * mentions are looked up directly, capped at twenty rows. If nothing comes back the model is never
 * called at all — an ungrounded answer is worse than no answer, so the refusal is returned instead.
 */
@Service
public class QuestionAnswerer {

    public static final String NOTHING_FOUND =
            "I could not find any rows in this batch that relate to that question, so I have nothing to answer from.";

    private static final int MAX_ROWS = 20;
    private static final Pattern ORDER_ID = Pattern.compile("\\bORD-[A-Za-z0-9-]+\\b");
    private static final Pattern UTR = Pattern.compile("\\bUTR[A-Za-z0-9]+\\b");
    private static final Pattern DATE = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern AMOUNT = Pattern.compile("(?<![\\d-])\\d+(?:\\.\\d{1,2})?(?![\\d-])");

    private final LlmGateway llm;
    private final IngestBatchRepository ingestBatchRepository;
    private final MerchantOrderRepository orderRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final BankEntryRepository bankEntryRepository;
    private final ExceptionRecordRepository exceptionRepository;
    private final String template;

    public QuestionAnswerer(LlmGateway llm,
                            IngestBatchRepository ingestBatchRepository,
                            MerchantOrderRepository orderRepository,
                            SettlementBatchRepository settlementBatchRepository,
                            BankEntryRepository bankEntryRepository,
                            ExceptionRecordRepository exceptionRepository) {
        this.llm = llm;
        this.ingestBatchRepository = ingestBatchRepository;
        this.orderRepository = orderRepository;
        this.settlementBatchRepository = settlementBatchRepository;
        this.bankEntryRepository = bankEntryRepository;
        this.exceptionRepository = exceptionRepository;
        this.template = LlmGateway.loadPrompt("question-answerer.txt");
    }

    @Transactional
    public AskResponse ask(UUID batchId, String question) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }

        Map<Long, String> rows = retrieve(batchId, question);
        if (rows.isEmpty()) {
            return new AskResponse(NOTHING_FOUND, List.of());
        }

        String rendered = rows.entrySet().stream()
                .map(row -> "[%d] %s".formatted(row.getKey(), row.getValue()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        String prompt = template.replace("{{question}}", question).replace("{{rows}}", rendered);
        AskResponse answer = llm.completeAs(batchId, "LLM_ASK", prompt, AskResponse.class);

        if (answer == null || answer.answer() == null || answer.answer().isBlank()) {
            return new AskResponse(NOTHING_FOUND, List.of());
        }
        List<Long> cited = answer.citedRowIds() == null
                ? List.of()
                : answer.citedRowIds().stream().filter(rows::containsKey).toList();
        return new AskResponse(answer.answer().trim(), cited);
    }

    /** Looks up exactly what the question names, so every row offered can be traced back to it. */
    private Map<Long, String> retrieve(UUID batchId, String question) {
        Map<Long, String> rows = new LinkedHashMap<>();

        for (String orderId : matches(ORDER_ID, question)) {
            orderRepository.findByBatchIdAndOrderId(batchId, orderId)
                    .forEach(order -> rows.put(order.getId(), describe(order)));
            exceptionRepository.findByBatchIdAndEntityRef(batchId, orderId)
                    .forEach(record -> rows.put(record.getId(), describe(record)));
        }
        for (String utr : matches(UTR, question)) {
            settlementBatchRepository.findByBatchIdAndUtr(batchId, utr)
                    .forEach(settlement -> rows.put(settlement.getId(), describe(settlement)));
            exceptionRepository.findByBatchIdAndEntityRef(batchId, utr)
                    .forEach(record -> rows.put(record.getId(), describe(record)));
        }
        for (String text : matches(DATE, question)) {
            LocalDate date = parseDate(text);
            if (date == null) {
                continue;
            }
            settlementBatchRepository.findByBatchIdAndSettledOn(batchId, date)
                    .forEach(settlement -> rows.put(settlement.getId(), describe(settlement)));
            bankEntryRepository.findByBatchIdAndEntryDate(batchId, date)
                    .forEach(entry -> rows.put(entry.getId(), describe(entry)));
        }
        for (String text : matches(AMOUNT, question)) {
            BigDecimal amount = new BigDecimal(text).setScale(2, java.math.RoundingMode.HALF_UP);
            orderRepository.findByBatchIdAndAmount(batchId, amount)
                    .forEach(order -> rows.put(order.getId(), describe(order)));
            bankEntryRepository.findByBatchIdAndAmount(batchId, amount)
                    .forEach(entry -> rows.put(entry.getId(), describe(entry)));
        }

        return rows.size() <= MAX_ROWS
                ? rows
                : rows.entrySet().stream().limit(MAX_ROWS)
                        .collect(LinkedHashMap::new, (map, e) -> map.put(e.getKey(), e.getValue()), Map::putAll);
    }

    private static List<String> matches(Pattern pattern, String question) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(question);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String describe(MerchantOrder order) {
        return "order orderId=%s placedAt=%s amount=%s"
                .formatted(order.getOrderId(), order.getOrderTs(), order.getAmount());
    }

    private static String describe(SettlementBatch settlement) {
        return "settlement utr=%s settledOn=%s amount=%s"
                .formatted(settlement.getUtr(), settlement.getSettledOn(), settlement.getAmount());
    }

    private static String describe(BankEntry entry) {
        return "bankCredit date=%s utr=%s amount=%s narration=%s"
                .formatted(entry.getEntryDate(), entry.getUtr(), entry.getAmount(), entry.getDescription());
    }

    private static String describe(ExceptionRecord record) {
        return "exception status=%s entity=%s confidence=%s reason=%s"
                .formatted(record.getStatus(), record.getEntityRef(), record.getConfidence(), record.getReason());
    }
}
