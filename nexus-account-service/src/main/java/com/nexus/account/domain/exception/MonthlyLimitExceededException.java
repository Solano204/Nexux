package com.nexus.account.domain.exception;
public class MonthlyLimitExceededException extends RuntimeException {
    public MonthlyLimitExceededException(String message) { super(message); }
}