package com.rentgg.deposit.application;

import com.rentgg.deposit.domain.Deposit;
import com.rentgg.deposit.domain.DepositStatus;

import java.time.Instant;
import java.util.UUID;

public record DepositView(UUID id, UUID orderId, UUID renterId, long amount, DepositStatus status, Instant holdUntil,
                          long deductedAmount, long refundedAmount) {

    static DepositView of(Deposit d) {
        return new DepositView(d.getId(), d.getOrderId(), d.getRenterId(), d.getAmount(), d.getStatus(),
                d.getHoldUntil(), d.getDeductedAmount(), d.getRefundedAmount());
    }
}
