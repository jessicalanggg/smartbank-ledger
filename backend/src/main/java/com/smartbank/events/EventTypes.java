package com.smartbank.events;

import java.util.Set;

public final class EventTypes {
    private EventTypes() { }
    public static final String TRANSACTION_POSTED = "transaction.posted";
    public static final String HOLD_CREATED = "hold.created";
    public static final String HOLD_POSTED = "hold.posted";
    public static final String HOLD_VOIDED = "hold.voided";
    public static final String HOLD_EXPIRED = "hold.expired";
    public static final String AGENT_PAYMENT_POSTED = "agent_payment.posted";
    public static final String AGENT_PAYMENT_APPROVAL_REQUESTED = "agent_payment.approval_requested";
    public static final String AGENT_PAYMENT_APPROVED = "agent_payment.approved";
    public static final String AGENT_PAYMENT_DECLINED = "agent_payment.declined";
    public static final String AGENT_PAYMENT_REJECTED = "agent_payment.rejected";
    public static final Set<String> ALL = Set.of(TRANSACTION_POSTED, HOLD_CREATED, HOLD_POSTED, HOLD_VOIDED, HOLD_EXPIRED,
            AGENT_PAYMENT_POSTED, AGENT_PAYMENT_APPROVAL_REQUESTED, AGENT_PAYMENT_APPROVED, AGENT_PAYMENT_DECLINED,
            AGENT_PAYMENT_REJECTED);
}
