package com.example.policyrag.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadValidatorTest {

    private final UploadValidator validator = new UploadValidator();

    @Test
    void acceptsPdfWhenChecksumMatches() throws Exception {
        byte[] bytes = "%PDF-1.7\nvalid-enough-for-upload-validation".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", bytes);

        assertThat(validator.validate("E1234", checksum(bytes), file)).isEqualTo(bytes);
    }

    @Test
    void rejectsChecksumMismatch() {
        byte[] bytes = "%PDF-1.7\ncontent".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> validator.validate("E123", "0".repeat(64), file))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessage("File checksum validation failed")
                .extracting(exception -> ((PolicyValidationException) exception).code())
                .isEqualTo("CHECKSUM_MISMATCH");
    }

    @Test
    void rejectsEmployeeIdsLongerThanFiveCharacters() {
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", "%PDF-".getBytes());

        assertThatThrownBy(() -> validator.validate("E12345", "0".repeat(64), file))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessage("Employee ID must not exceed 5 characters");
    }

    @Test
    void rejectsFilesLargerThanTwentyFiveKb() {
        byte[] bytes = new byte[(int) UploadValidator.MAX_FILE_SIZE + 1];
        bytes[0] = '%'; bytes[1] = 'P'; bytes[2] = 'D'; bytes[3] = 'F'; bytes[4] = '-';
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> validator.validate("E123", "0".repeat(64), file))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessage("File size must not exceed 25 KB");
    }

    @Test
    void rejectsRenamedNonPdfContent() {
        byte[] bytes = "not a pdf".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> validator.validate("E123", "0".repeat(64), file))
                .isInstanceOf(PolicyValidationException.class)
                .hasMessage("The selected file is not a valid PDF");
    }

    private String checksum(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
