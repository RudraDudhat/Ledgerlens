package com.ledgerlens.domain;

public enum ExceptionStatus {
    MATCHED,
    PAYMENT_FAILED,
    HELD_DISPUTE,
    REFUND_PRIOR_CYCLE,
    BANK_DUPLICATE,
    BANK_MISSING,
    AMOUNT_MISMATCH,
    UNKNOWN
}
