package com.example.policyrag.service;

public class PolicyValidationException extends RuntimeException {

    private final String code;

    public PolicyValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
