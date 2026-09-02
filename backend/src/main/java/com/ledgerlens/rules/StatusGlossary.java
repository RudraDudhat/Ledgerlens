package com.ledgerlens.rules;

import java.util.Map;

/**
 * What each exception status means for someone who does not work in payments.
 *
 * <p>Written once and read everywhere it is explained: the PDF statement prints these under each
 * group heading, and the Ask panel's conceptual answers are grounded in the same wording. Two
 * definitions of one status is how a product ends up telling a merchant different things on
 * different screens.
 */
public final class StatusGlossary {

    private static final String FALLBACK = "These need a human eye.";

    private static final Map<String, String> MEANING = Map.of(
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

    private StatusGlossary() {
    }

    public static String meaningOf(String status) {
        return MEANING.getOrDefault(status, FALLBACK);
    }

    public static Map<String, String> all() {
        return MEANING;
    }

    /** One "STATUS — meaning" line per status, for a prompt that has to define them. */
    public static String rendered() {
        return MEANING.entrySet().stream()
                .map(entry -> "%s — %s".formatted(entry.getKey(), entry.getValue()))
                .sorted()
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }
}
