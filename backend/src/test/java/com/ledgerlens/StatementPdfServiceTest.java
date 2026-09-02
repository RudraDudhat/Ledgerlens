package com.ledgerlens;

import com.ledgerlens.dto.ExceptionView;
import com.ledgerlens.dto.IngestResponse;
import com.ledgerlens.dto.WaterfallStep;
import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.repository.AuditLogRepository;
import com.ledgerlens.service.CsvIngestService;
import com.ledgerlens.service.ReconciliationService;
import com.ledgerlens.service.StatementPdfService;
import com.ledgerlens.service.SyntheticDataWriter;
import com.ledgerlens.service.WaterfallService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the committed 300-row batch and reads the PDF back with PDFBox.
 *
 * <p>The statement is a report on numbers that were settled long before it is asked for, so the
 * model has no business being called here. The stub is wired in and asserted never to have been
 * handed a prompt — a statement that costs an API call every time it is downloaded is a statement
 * nobody can afford to regenerate.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.google.genai.api-key=stub-key-never-sent",
        "spring.ai.google.genai.chat.options.model=gemini-3.6-flash",
        "ledgerlens.merchant-name=Chai Point Traders"
})
@Import(StatementPdfServiceTest.StubChatModelConfiguration.class)
class StatementPdfServiceTest {

    private static final Path DATA_DIR = Path.of("..", "data");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    @TestConfiguration
    static class StubChatModelConfiguration {
        @Bean
        @Primary
        StubChatModel stubChatModel() {
            return new StubChatModel();
        }
    }

    @Autowired
    CsvIngestService ingestService;
    @Autowired
    ReconciliationService reconciliationService;
    @Autowired
    StatementPdfService statementPdfService;
    @Autowired
    AuditLogRepository auditLogRepository;
    @Autowired
    WaterfallService waterfallService;
    @Autowired
    StubChatModel chatModel;

    @BeforeEach
    void resetModel() {
        chatModel.reset();
    }

    @Test
    void rendersAReadablePdfForTheCommittedBatch() throws IOException {
        UUID batchId = reconciledBatch();

        StatementPdfService.Statement statement = statementPdfService.render(batchId);

        assertThat(statement.pdf()).startsWith('%', 'P', 'D', 'F', '-');
        // One to two pages is the point of the thing: past that it stops being forwardable.
        assertThat(statement.pages()).isBetween(1, 2);

        String text = textOf(statement.pdf());
        assertThat(text).contains("Settlement Statement");
        assertThat(text).contains("Chai Point Traders");
        assertThat(text).contains("What you sold");
        assertThat(text).contains("What reached your bank");
    }

    /** The headline numbers have to survive into the PDF grouped the Indian way, not as raw digits. */
    @Test
    void printsTheTotalsWithIndianGrouping() throws IOException {
        UUID batchId = reconciledBatch();
        var summary = reconciliationService.summary(batchId);

        String text = textOf(statementPdfService.render(batchId).pdf());

        assertThat(text).contains(grouped(summary.grossSales()));
        assertThat(text).contains(grouped(summary.totalBankCredits()));
        assertThat(text).contains("₹");
        assertThat(text).doesNotContain(summary.grossSales().toPlainString());
    }

    /**
     * The money story owns page one and the exception list owns page two. Splitting the waterfall or
     * the plain-words paragraph across the fold is what makes a statement unreadable on a phone.
     */
    @Test
    void keepsTheMoneyStoryOnPageOneAndTheExceptionsOnPageTwo() throws IOException {
        UUID batchId = reconciledBatch();

        byte[] pdf = statementPdfService.render(batchId).pdf();

        String first = textOfPage(pdf, 1);
        assertThat(first).containsIgnoringCase("Where the money went");
        assertThat(first).containsIgnoringCase("In plain words");
        assertThat(first).doesNotContainIgnoringCase("What needs your attention");

        String second = textOfPage(pdf, 2);
        assertThat(second).containsIgnoringCase("What needs your attention");
        // The whole section has to land on that page rather than running onto a third.
        assertThat(second).contains("payouts your bank has not credited");
        assertThat(second).contains("failed payments");
    }

    @Test
    void neverCallsTheModel() throws IOException {
        UUID batchId = reconciledBatch();

        statementPdfService.render(batchId);

        assertThat(chatModel.prompts()).isEmpty();
    }

    /** Without a narration the statement still renders; it says so rather than leaving a gap. */
    @Test
    void survivesABatchThatWasNeverNarrated() throws IOException {
        UUID batchId = reconciledBatch();

        String text = textOf(statementPdfService.render(batchId).pdf());

        // The section headings are set in small caps by the stylesheet, so match on the words only.
        assertThat(text).containsIgnoringCase("In plain words");
        assertThat(text).contains("No plain-words summary was written");
    }

    @Test
    void reusesTheNarrationAlreadyStoredForTheBatch() throws IOException {
        UUID batchId = reconciledBatch();
        // Exactly what the gateway writes when the narrator runs, and the only place it is kept.
        AuditLog narration = new AuditLog();
        narration.setLoggedAt(java.time.LocalDateTime.now());
        narration.setBatchId(batchId);
        narration.setAction("LLM_NARRATE");
        narration.setDetail("model=gemini promptSha256=abc latencyMs=12 "
                + "output=Fees and refunds took the difference.");
        auditLogRepository.save(narration);

        String text = textOf(statementPdfService.render(batchId).pdf());

        assertThat(text).contains("Fees and refunds took the difference.");
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void logsTheGeneratedStatementWithItsPageCount() throws IOException {
        UUID batchId = reconciledBatch();

        StatementPdfService.Statement statement = statementPdfService.render(batchId);

        List<AuditLog> written = auditLogRepository.findByBatchIdOrderById(batchId).stream()
                .filter(entry -> "STATEMENT_PDF".equals(entry.getAction()))
                .toList();
        assertThat(written).hasSize(1);
        assertThat(written.get(0).getDetail())
                .contains("statement generated")
                .contains("pages=" + statement.pages());
    }

    /**
     * The section is a to-do list, so it lists only what the reader can act on. Failed payments and
     * refunds are stated once as a total: the money is already in the waterfall, and listing them
     * order by order buries the payouts the bank never credited.
     */
    @Test
    void statesUnactionableExceptionsAsATotalRatherThanListingThem() throws IOException {
        UUID batchId = reconciledBatch();
        List<ExceptionView> all = reconciliationService.exceptions(batchId);
        List<String> failedRefs = all.stream()
                .filter(view -> "PAYMENT_FAILED".equals(view.status()))
                .map(ExceptionView::entityRef)
                .toList();

        String text = textOf(statementPdfService.render(batchId).pdf());

        assertThat(failedRefs).isNotEmpty();
        assertThat(text)
                .as("a failed payment is not a task, so no order id of one should be printed")
                .doesNotContain(failedRefs);
        assertThat(text).contains("failed payments");

        // Every bank-level finding is listed in full, with the UTR to quote at the bank.
        List<String> bankRefs = all.stream()
                .filter(view -> "BANK_MISSING".equals(view.status()))
                .map(ExceptionView::entityRef)
                .toList();
        assertThat(bankRefs).isNotEmpty();
        bankRefs.forEach(ref -> assertThat(text).contains(ref));
    }

    /**
     * A summary line has to quote the waterfall, not re-add the rows underneath it: refunds whose
     * order predates the batch have no row, so the two disagree, and a statement must not contradict
     * itself across a page fold.
     */
    @Test
    void takesSummaryTotalsFromTheWaterfallNotFromTheRows() throws IOException {
        UUID batchId = reconciledBatch();
        List<WaterfallStep> steps = waterfallService.waterfall(batchId);

        String text = textOf(statementPdfService.render(batchId).pdf());

        for (String label : List.of("Refunds", "Held for disputes", "Failed payments")) {
            BigDecimal fromWaterfall = steps.stream()
                    .filter(step -> step.label().equals(label))
                    .map(WaterfallStep::amount)
                    .findFirst()
                    .orElseThrow();
            assertThat(text)
                    .as("the summary line for %s must show the waterfall's own figure", label)
                    .contains(grouped(fromWaterfall.abs()));
        }
    }

    /** Bank-level findings are keyed by UTR, and used to reach the page with no amount at all. */
    @Test
    void givesBankLevelExceptionsTheMoneyAtStake() throws IOException {
        UUID batchId = reconciledBatch();

        List<ExceptionView> bankLevel = reconciliationService.exceptions(batchId).stream()
                .filter(view -> List.of("BANK_MISSING", "BANK_DUPLICATE", "AMOUNT_MISMATCH")
                        .contains(view.status()))
                .toList();

        assertThat(bankLevel).isNotEmpty();
        assertThat(bankLevel).allSatisfy(view -> {
            assertThat(view.amount()).as("%s %s carries no amount", view.status(), view.entityRef()).isNotNull();
            assertThat(view.amount().signum()).isPositive();
        });
    }

    private UUID reconciledBatch() throws IOException {
        IngestResponse response = ingestService.ingest(
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.ORDERS_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.SETTLEMENT_FILE)),
                Files.newInputStream(DATA_DIR.resolve(SyntheticDataWriter.BANK_FILE)));
        reconciliationService.reconcile(response.batchId());
        return response.batchId();
    }

    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String textOfPage(byte[] pdf, int page) throws IOException {
        try (PDDocument document = PDDocument.load(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThanOrEqualTo(page);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            return stripper.getText(document);
        }
    }

    private static String grouped(java.math.BigDecimal value) {
        var format = java.text.NumberFormat.getInstance(java.util.Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }
}
