package com.example.policyrag.api;

import com.fasterxml.jackson.annotation.JsonAlias;

public record ChatRequest(@JsonAlias("employeeid") String employeeId, String prompt) {
}
