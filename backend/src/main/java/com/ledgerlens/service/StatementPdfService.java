package com.ledgerlens.service;

import com.ledgerlens.dto.AlertView;
import com.ledgerlens.dto.ExceptionView;
import com.ledgerlens.dto.HealthReport;
import com.ledgerlens.dto.ReconcileSummary;
import com.ledgerlens.dto.WaterfallStep;
import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.entity.MerchantOrder;
import com.ledgerlens.repository.AuditLogRepository;
import com.ledgerlens.repository.IngestBatchRepository;
import com.ledgerlens.repository.MatchRecordRepository;
import com.ledgerlens.repository.MerchantOrderRepository;
import com.openhtmltopdf.extend.FSSupplier;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The statement a founder can read on a phone and forward to an accountant.
 *
 * <p>Everything in it is already computed. The waterfall, the exceptions and the alerts are read
 * back from the batch, and the plain-words paragraph is the narration this batch was already given
 * rather than a fresh one, so generating a statement never calls the model, never costs anything,
 * and says the same thing every time it is asked about the same batch.
 */
@Service
public class StatementPdfService {

    static final String STATEMENT_ACTION = "STATEMENT_PDF";

    /**
     * At most this many exception rows, then a line saying how many were left out. Sized so that the
     * worst case — every row in its own group, plus the three summary lines under them — still lands
     * on the second page at the spacing the stylesheet sets.
     */
    public static final int EXCEPTION_ROW_CAP = 12;

    /** As much narration as page one has room for, measured against a full ten-row waterfall. */
    static final int NARRATIVE_CHAR_CAP = 900;

    private static final String FONT_FAMILY = "DejaVu Sans";
    private static final String FONT_REGULAR = "net/sf/jasperreports/fonts/dejavu/DejaVuSans.ttf";
    private static final String FONT_BOLD = "net/sf/jasperreports/fonts/dejavu/DejaVuSans-Bold.ttf";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ENGLISH);

    /**
     * What each status means for someone who does not work in payments. Written once, here, so the
     * statement explains itself instead of repeating the reconciler's internal vocabulary.
     */
    private static final Map<String, String> STATUS_MEANING = Map.of(
            "PAYMENT_FAILED",
            "The payment never went through, so no money was collected and none is owed to you.",
            "HELD_DISPUTE",
            "Customers disputed these payments, so Razorpay is holding the money until each one resolves.",
            "REFUND_PRIOR_CYCLE",
            "You refunded these after the original payment had already been paid out, so the money came back "
                    + "out of a later payout.",
            "BANK_DUPLICATE",
            "The same credit appears twice in your bank statement. Only one of them is a real payout, so this "
                    + "is worth raising with your bank.",
            "BANK_MISSING",
            "Razorpay says it paid out, but no matching credit has reached your bank yet.",
            "AMOUNT_MISMATCH",
            "The amount Razorpay settled and the amount your bank credited do not agree.",
            "UNKNOWN",
            "These could not be explained by any rule and need a human eye.",
            "MATCHED",
            "These lined up cleanly and need nothing from you.");

    /**
     * Statuses stated as one total rather than listed, mapped to the waterfall step that carries the
     * money. Nothing here is a task: the reader cannot un-fail a payment or un-refund an order, and
     * a dispute resolves on Razorpay's clock, not theirs.
     */
    private static final Map<String, String> SUMMARISED = Map.of(
            "HELD_DISPUTE", WaterfallService.HELD,
            "REFUND_PRIOR_CYCLE", WaterfallService.REFUNDS,
            "PAYMENT_FAILED", WaterfallService.FAILED_PAYMENTS);

    private static final Map<String, String> METRIC_NAMES = Map.of(
            "fee_rate", "Fees and GST as a share of sales",
            "failure_rate", "Share of payments that failed",
            "dispute_rate", "Share of payments disputed",
            "avg_settlement_delay_days", "Average days from sale to payout",
            "match_rate", "Share of orders matched to a payout");

    private final IngestBatchRepository ingestBatchRepository;
    private final MatchRecordRepository matchRepository;
    private final MerchantOrderRepository orderRepository;
    private final AuditLogRepository auditLogRepository;
    private final ReconciliationService reconciliationService;
    private final WaterfallService waterfallService;
    private final WaterfallNarrator narrator;
    private final HealthService healthService;
    private final String merchantName;
    private final String template;

    public StatementPdfService(IngestBatchRepository ingestBatchRepository,
                               MatchRecordRepository matchRepository,
                               MerchantOrderRepository orderRepository,
                               AuditLogRepository auditLogRepository,
                               ReconciliationService reconciliationService,
                               WaterfallService waterfallService,
                               WaterfallNarrator narrator,
                               HealthService healthService,
                               @Value("${ledgerlens.merchant-name:Your business}") String merchantName) {
        this.ingestBatchRepository = ingestBatchRepository;
        this.matchRepository = matchRepository;
        this.orderRepository = orderRepository;
        this.auditLogRepository = auditLogRepository;
        this.reconciliationService = reconciliationService;
        this.waterfallService = waterfallService;
        this.narrator = narrator;
        this.healthService = healthService;
        this.merchantName = merchantName;
        this.template = loadTemplate();
    }

    /** The rendered statement, plus the period it covers so the caller can name the file. */
    public record Statement(byte[] pdf, String period, int pages) {
    }

    /** Reads throughout, but the audit row at the end is a write, so this cannot be read-only. */
    @Transactional
    public Statement render(UUID batchId) {
        if (!ingestBatchRepository.existsById(batchId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown batch " + batchId);
        }
        if (matchRepository.countByBatchId(batchId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "batch " + batchId + " has not been reconciled yet");
        }

        ReconcileSummary summary = reconciliationService.summary(batchId);
        List<WaterfallStep> steps = waterfallService.waterfall(batchId);
        List<ExceptionView> exceptions = reconciliationService.exceptions(batchId);
        List<MerchantOrder> orders = orderRepository.findByBatchIdOrderById(batchId);
        HealthReport health = healthService.report(batchId);
        Optional<String> narrative = narrator.storedNarrative(batchId);

        Period period = periodOf(orders);
        String html = fill(batchId, summary, steps, exceptions, orders, health, narrative, period);
        byte[] pdf = toPdf(html);
        int pages = pageCount(pdf);

        AuditLog entry = new AuditLog();
        entry.setLoggedAt(LocalDateTime.now());
        entry.setBatchId(batchId);
        entry.setAction(STATEMENT_ACTION);
        entry.setDetail("statement generated pages=%d period=%s exceptions=%d"
                .formatted(pages, period.fileLabel(), exceptions.size()));
        auditLogRepository.save(entry);

        return new Statement(pdf, period.fileLabel(), pages);
    }

    // ------------------------------------------------------------------ template

    private String fill(UUID batchId,
                        ReconcileSummary summary,
                        List<WaterfallStep> steps,
                        List<ExceptionView> exceptions,
                        List<MerchantOrder> orders,
                        HealthReport health,
                        Optional<String> narrative,
                        Period period) {
        BigDecimal sales = summary.grossSales() == null ? BigDecimal.ZERO : summary.grossSales();
        BigDecimal received = summary.totalBankCredits() == null ? BigDecimal.ZERO : summary.totalBankCredits();

        return template
                .replace("{{merchant}}", escape(merchantName))
                .replace("{{period}}", escape(period.label()))
                .replace("{{generatedAt}}", escape(LocalDateTime.now().format(STAMP)))
                .replace("{{batchId}}", escape(batchId.toString()))
                .replace("{{headline}}", headline(sales, received))
                .replace("{{waterfallRows}}", waterfallRows(steps, sales, received))
                .replace("{{reconcileNote}}", reconcileNote(steps, received))
                .replace("{{plainWords}}", plainWords(narrative))
                .replace("{{attention}}", attention(exceptions, orders, steps))
                .replace("{{changes}}", changes(health));
    }

    /** Numbers first, and the same three numbers the rest of the statement then breaks down. */
    private static String headline(BigDecimal sales, BigDecimal received) {
        BigDecimal difference = sales.subtract(received);
        if (difference.signum() == 0) {
            return escape("You sold " + rupees(sales) + ", and all of it reached your bank.");
        }
        return escape("You sold %s. %s reached your bank. Here is where the %s difference went."
                .formatted(rupees(sales), rupees(received), rupees(difference.abs())));
    }

    private static String waterfallRows(List<WaterfallStep> steps, BigDecimal sales, BigDecimal received) {
        StringBuilder rows = new StringBuilder();
        BigDecimal running = BigDecimal.ZERO;
        boolean first = true;
        for (WaterfallStep step : steps) {
            running = running.add(step.amount());
            rows.append(row(first ? "What you sold" : step.label(), step.amount(), sales, false));
            first = false;
        }
        rows.append(row("What reached your bank", steps.isEmpty() ? received : running, sales, true));
        return rows.toString();
    }

    private static String row(String label, BigDecimal amount, BigDecimal sales, boolean total) {
        BigDecimal share = shareOfSales(amount, sales);
        // Money taken away is drawn solid and money put back is drawn light. The sign column already
        // says which is which, so the table still reads correctly printed in black and white.
        String fill = amount.signum() < 0 ? "bar-fill" : "bar-fill-open";
        return """
                    <tr%s>
                      <td>%s</td>
                      <td class="col-amount">%s</td>
                      <td class="col-share">%s</td>
                      <td class="col-bar"><div class="bar-track"><div class="%s" style="width: %s%%;"></div></div></td>
                    </tr>
                """.formatted(
                total ? " class=\"total\"" : "",
                escape(label),
                // Every other row is a signed delta; the last one is a total, and totals carry no sign.
                escape(total ? rupees(amount) : signedRupees(amount)),
                escape(share.toPlainString() + "%"),
                fill,
                barWidth(share));
    }

    private static String reconcileNote(List<WaterfallStep> steps, BigDecimal received) {
        BigDecimal walked = steps.stream().map(WaterfallStep::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal drift = walked.subtract(received);
        return drift.signum() == 0
                ? escape("Reconciles to the rupee ✓")
                : escape("Unexplained: " + rupees(drift.abs()));
    }

    private static String plainWords(Optional<String> narrative) {
        return narrative
                .map(text -> "<p class=\"plain\">" + escape(fitToPage(text)) + "</p>")
                .orElse("<p class=\"empty\">No plain-words summary was written for this batch. "
                        + "Every number above is computed from your files and is unaffected.</p>");
    }

    /**
     * Holds the narration to what the first page can take.
     *
     * <p>The prompt asks for 120 words, but a prompt is a request. A narration twice that long
     * pushes the waterfall onto a second page and the exception list onto a third, and the whole
     * point of the layout is that the money story is one page. Cut at the last full sentence that
     * fits, so what remains still reads as prose rather than as a severed clause.
     */
    private static String fitToPage(String text) {
        String trimmed = text.trim();
        if (trimmed.length() <= NARRATIVE_CHAR_CAP) {
            return trimmed;
        }
        String window = trimmed.substring(0, NARRATIVE_CHAR_CAP);
        int lastSentence = window.lastIndexOf(". ");
        if (lastSentence > NARRATIVE_CHAR_CAP / 2) {
            return window.substring(0, lastSentence + 1);
        }
        int lastSpace = window.lastIndexOf(' ');
        return (lastSpace > 0 ? window.substring(0, lastSpace) : window) + "…";
    }

    /**
     * A row earns a line here only if the reader has to do something about it.
     *
     * <p>A refund from an earlier cycle and a payment that never went through are explanations, not
     * tasks: the money is already accounted for in the waterfall above, and listing them order by
     * order fills the page with rows nobody can act on while a payout the bank never credited goes
     * unmentioned. Those two, and disputes, are stated once each as a total; everything that is
     * actually wrong or missing is listed in full.
     */
    private static String attention(List<ExceptionView> exceptions,
                                    List<MerchantOrder> orders,
                                    List<WaterfallStep> steps) {
        if (exceptions.isEmpty()) {
            return "<p class=\"empty\">Nothing needs your attention: every order was matched to a payout.</p>";
        }

        Map<String, LocalDateTime> whenByOrder = new HashMap<>();
        orders.forEach(order -> whenByOrder.put(order.getOrderId(), order.getOrderTs()));

        List<ExceptionView> actionable = exceptions.stream()
                .filter(view -> !SUMMARISED.containsKey(view.status()))
                .toList();

        // Money missing before money merely disagreeing, biggest group first within a rank, so a
        // truncation drops the least urgent rows rather than whichever group happens to be smallest.
        Map<String, List<ExceptionView>> grouped = new LinkedHashMap<>();
        actionable.stream()
                .collect(Collectors.groupingBy(ExceptionView::status))
                .entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<ExceptionView>>>comparingInt(e -> urgencyOf(e.getKey()))
                        .thenComparing(e -> e.getValue().size(), Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .forEach(entry -> grouped.put(entry.getKey(), entry.getValue()));

        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, List<ExceptionView>> group : grouped.entrySet()) {
            if (shown >= EXCEPTION_ROW_CAP) {
                break;
            }
            List<ExceptionView> rows = group.getValue();
            out.append("<div class=\"group\"><div class=\"group-head\">")
                    .append(escape(headingFor(group.getKey(), rows.size())))
                    .append("</div><p class=\"group-why\">")
                    .append(escape(STATUS_MEANING.getOrDefault(group.getKey(), "These need a human eye.")))
                    .append("</p><table class=\"rows\">");
            for (ExceptionView view : rows) {
                if (shown >= EXCEPTION_ROW_CAP) {
                    break;
                }
                LocalDateTime when = whenByOrder.get(view.entityRef());
                out.append("""
                            <tr>
                              <td class="ref">%s</td>
                              <td class="amt">%s</td>
                              <td class="when">%s</td>
                              <td class="why">%s</td>
                            </tr>
                        """.formatted(
                        escape(view.entityRef() == null ? "—" : view.entityRef()),
                        escape(view.amount() == null ? "—" : rupees(view.amount())),
                        escape(when == null ? "—" : when.toLocalDate().format(DAY)),
                        escape(view.reason() == null ? "" : view.reason())));
                shown++;
            }
            out.append("</table></div>");
        }

        int remaining = actionable.size() - shown;
        if (remaining > 0) {
            out.append("<p class=\"more\">… and ")
                    .append(remaining)
                    .append(" more in the dashboard.</p>");
        }

        // Last, and without rows: these close the section rather than opening it.
        exceptions.stream()
                .map(ExceptionView::status)
                .distinct()
                .filter(SUMMARISED::containsKey)
                .sorted(Comparator.comparingInt(status -> urgencyOf(status)))
                .forEach(status -> out.append(summaryLine(status, exceptions, steps)));

        return out.toString();
    }

    /** Lower is more urgent. Ranks the listed groups and orders the summary lines under them. */
    private static int urgencyOf(String status) {
        return switch (status) {
            case "BANK_MISSING" -> 0;
            case "BANK_DUPLICATE" -> 1;
            case "AMOUNT_MISMATCH" -> 2;
            case "UNKNOWN" -> 3;
            case "HELD_DISPUTE" -> 4;
            case "REFUND_PRIOR_CYCLE" -> 5;
            default -> 6;
        };
    }

    /**
     * One line for a whole status: how many, how much, and why it needs nothing.
     *
     * <p>The total comes from the waterfall step, never from re-adding the exception rows. The two
     * do not agree — a refund whose order predates the batch has no row to add, and a held payment's
     * row carries its gross while the waterfall holds back the net — and a statement whose second
     * page contradicts its own first page is worse than one that says less.
     */
    private static String summaryLine(String status, List<ExceptionView> exceptions, List<WaterfallStep> steps) {
        long count = exceptions.stream().filter(view -> status.equals(view.status())).count();
        BigDecimal total = stepAmount(steps, SUMMARISED.get(status));
        String heading = headingFor(status, (int) count) + (total == null ? "" : " — " + rupees(total.abs()));
        return "<div class=\"group\"><div class=\"group-head\">"
                + escape(heading)
                + "</div><p class=\"group-why\">"
                + escape(STATUS_MEANING.getOrDefault(status, "These need a human eye.")
                        + " Each one is listed in the dashboard.")
                + "</p></div>";
    }

    private static BigDecimal stepAmount(List<WaterfallStep> steps, String label) {
        return steps.stream()
                .filter(step -> step.label().equals(label))
                .map(WaterfallStep::amount)
                .findFirst()
                .orElse(null);
    }

    private static String headingFor(String status, int count) {
        String orders = count == 1 ? "order" : "orders";
        return switch (status) {
            case "PAYMENT_FAILED" -> count + " failed " + (count == 1 ? "payment" : "payments");
            case "HELD_DISPUTE" -> count + " disputed " + (count == 1 ? "payment" : "payments");
            case "REFUND_PRIOR_CYCLE" -> count + " " + (count == 1 ? "refund" : "refunds") + " from an earlier payout";
            case "BANK_DUPLICATE" -> count + " duplicate bank " + (count == 1 ? "credit" : "credits");
            case "BANK_MISSING" -> count + " payout" + (count == 1 ? "" : "s") + " your bank has not credited";
            case "AMOUNT_MISMATCH" -> count + " " + orders + " where the amounts disagree";
            case "MATCHED" -> count + " matched " + orders;
            default -> count + " unexplained " + orders;
        };
    }

    private static String changes(HealthReport health) {
        List<AlertView> alerts = health == null ? List.of() : health.alerts();
        if (alerts.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>Changes from your usual pattern</h2><ul class=\"alerts\">");
        for (AlertView alert : alerts) {
            out.append("<li>")
                    .append(escape(metricName(alert.metric())))
                    .append(escape(" is " + metricValue(alert.metric(), alert.currentValue())
                            + ", against a usual " + metricValue(alert.metric(), alert.baselineValue()) + "."));
            if (alert.likelyCause() != null && !alert.likelyCause().isBlank()) {
                out.append(" <span class=\"cause\">Likely cause: ")
                        .append(escape(alert.likelyCause().trim()))
                        .append("</span>");
            }
            out.append("</li>");
        }
        return out.append("</ul>").toString();
    }

    private static String metricName(String metric) {
        String known = METRIC_NAMES.get(metric);
        if (known != null) {
            return known;
        }
        if (metric != null && metric.startsWith("failure_rate_hour_")) {
            return "Failed payments around " + metric.substring("failure_rate_hour_".length()) + ":00";
        }
        return metric == null ? "A metric" : metric.replace('_', ' ');
    }

    private static String metricValue(String metric, BigDecimal value) {
        if (value == null) {
            return "—";
        }
        if ("avg_settlement_delay_days".equals(metric)) {
            return value.setScale(1, RoundingMode.HALF_UP).toPlainString() + " days";
        }
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    // ----------------------------------------------------------------- rendering

    static {
        // The renderer narrates every parse and font load at INFO on its own logger, which would put
        // twenty lines in the application log for each download. Failures still arrive as exceptions.
        XRLog.setLoggingEnabled(false);
    }

    private static byte[] toPdf(String html) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFont(classpathFont(FONT_REGULAR), FONT_FAMILY, 400, PdfRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(classpathFont(FONT_BOLD), FONT_FAMILY, 700, PdfRendererBuilder.FontStyle.NORMAL, true);
        builder.withHtmlContent(html, null);
        builder.toStream(out);
        try {
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("could not render the statement", e);
        }
        return out.toByteArray();
    }

    private static FSSupplier<InputStream> classpathFont(String path) {
        return () -> {
            try {
                return new ClassPathResource(path).getInputStream();
            } catch (IOException e) {
                throw new UncheckedIOException("missing statement font " + path, e);
            }
        };
    }

    private static int pageCount(byte[] pdf) {
        try (PDDocument document = PDDocument.load(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read back the statement", e);
        }
    }

    private static String loadTemplate() {
        try (InputStream stream = new ClassPathResource("templates/statement.html").getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing templates/statement.html", e);
        }
    }

    // ---------------------------------------------------------------- formatting

    /** The window the orders in this batch actually cover, which is what the reader recognises. */
    record Period(LocalDate from, LocalDate to) {

        String label() {
            if (from == null || to == null) {
                return "Period not known";
            }
            return from.equals(to) ? from.format(DAY) : from.format(DAY) + " – " + to.format(DAY);
        }

        String fileLabel() {
            if (from == null || to == null) {
                return "undated";
            }
            return from.equals(to) ? from.format(FILE_DAY) : from.format(FILE_DAY) + "_to_" + to.format(FILE_DAY);
        }
    }

    private static Period periodOf(List<MerchantOrder> orders) {
        List<LocalDate> days = orders.stream()
                .map(MerchantOrder::getOrderTs)
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .sorted()
                .toList();
        return days.isEmpty() ? new Period(null, null) : new Period(days.get(0), days.get(days.size() - 1));
    }

    private static BigDecimal shareOfSales(BigDecimal amount, BigDecimal sales) {
        if (sales == null || sales.signum() == 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return amount.abs().multiply(BigDecimal.valueOf(100)).divide(sales.abs(), 1, RoundingMode.HALF_UP);
    }

    private static String barWidth(BigDecimal share) {
        return share.min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO).setScale(1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String rupees(BigDecimal value) {
        NumberFormat format = NumberFormat.getInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "₹" + format.format(value);
    }

    private static String signedRupees(BigDecimal value) {
        if (value.signum() == 0) {
            return rupees(value);
        }
        return (value.signum() > 0 ? "+" : "−") + rupees(value.abs());
    }

    /** The template is parsed as XML, so every interpolated value has to be well-formed inside it. */
    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
