package com.nexus.account.domain.exception;
public class AccountFrozenException extends RuntimeException {
    public AccountFrozenException(String message) { super(message); }
}