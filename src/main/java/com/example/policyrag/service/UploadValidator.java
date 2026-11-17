package com.example.policyrag.service;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Component
public class UploadValidator {

    public static final long MAX_FILE_SIZE = 25L * 1024L;

    public byte[] validate(String employeeId, String expectedChecksum, MultipartFile file) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new PolicyValidationException("EMPLOYEE_ID_REQUIRED", "Employee ID is required");
        }
        if (employeeId.length() > 5) {
            throw new PolicyValidationException("EMPLOYEE_ID_TOO_LONG", "Employee ID must not exceed 5 characters");
        }
        if (file == null || file.isEmpty()) {
            throw new PolicyValidationException("FILE_REQUIRED", "Please select a PDF file");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new PolicyValidationException("FILE_TOO_LARGE", "File size must not exceed 25 KB");
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new PolicyValidationException("INVALID_FILE_TYPE", "Only PDF files are allowed");
        }
        if (expectedChecksum == null || !expectedChecksum.matches("(?i)^[0-9a-f]{64}$")) {
            throw new PolicyValidationException("INVALID_CHECKSUM", "A valid SHA-256 checksum is required");
        }

        try {
            byte[] content = file.getBytes();
            if (!hasPdfSignature(content)) {
                throw new PolicyValidationException("INVALID_PDF", "The selected file is not a valid PDF");
            }
            String actualChecksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            if (!MessageDigest.isEqual(actualChecksum.getBytes(), expectedChecksum.toLowerCase(Locale.ROOT).getBytes())) {
                throw new PolicyValidationException("CHECKSUM_MISMATCH", "File checksum validation failed");
            }
            return content;
        } catch (IOException exception) {
            throw new PolicyValidationException("FILE_READ_FAILED", "The uploaded file could not be read");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private boolean hasPdfSignature(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-';
    }
}
