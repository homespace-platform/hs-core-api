package com.hs.payment.model.constant;
/** Workflow routing only. Neither action acknowledges a bank receipt or changes the debt. */
public enum DisputeResolution { REVIEW_TRANSFER, REOPEN_TRANSFER }
