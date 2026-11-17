package com.example.policyrag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class PdfTextExtractor {

    public String extract(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setParagraphEnd("\n\n");
            String text = stripper.getText(document).trim();
            if (text.isBlank()) {
                throw new PolicyValidationException("EMPTY_PDF", "The PDF does not contain extractable text");
            }
            return text;
        } catch (PolicyValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PolicyValidationException("INVALID_PDF", "The selected file is damaged or is not a valid PDF");
        }
    }
}
