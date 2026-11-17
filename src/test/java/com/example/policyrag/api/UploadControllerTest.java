package com.example.policyrag.api;

import com.example.policyrag.service.PolicyIngestionService;
import com.example.policyrag.service.PolicyValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadControllerTest {

    private PolicyIngestionService ingestionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ingestionService = mock(PolicyIngestionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UploadController(ingestionService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsSuccessMessageAndStoredChunkCount() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", "%PDF-".getBytes());
        when(ingestionService.ingest(eq("E123"), eq("a".repeat(64)), any())).thenReturn(3);

        mockMvc.perform(multipart("/upload")
                        .file(file)
                        .param("employeeId", "E123")
                        .param("checksum", "a".repeat(64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("File Uploaded Successfully"))
                .andExpect(jsonPath("$.chunksStored").value(3));
    }

    @Test
    void exposesValidationMessageToTheUi() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", "%PDF-".getBytes());
        when(ingestionService.ingest(eq("E12345"), eq("a".repeat(64)), any()))
                .thenThrow(new PolicyValidationException("EMPLOYEE_ID_TOO_LONG",
                        "Employee ID must not exceed 5 characters"));

        mockMvc.perform(multipart("/upload")
                        .file(file)
                        .param("employeeId", "E12345")
                        .param("checksum", "a".repeat(64)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPLOYEE_ID_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("Employee ID must not exceed 5 characters"));
    }
}
