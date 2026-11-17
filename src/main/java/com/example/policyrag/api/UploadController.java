package com.example.policyrag.api;

import com.example.policyrag.service.PolicyIngestionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class UploadController {

    private final PolicyIngestionService ingestionService;

    public UploadController(PolicyIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadResponse upload(@RequestParam String employeeId,
                                 @RequestParam String checksum,
                                 @RequestParam MultipartFile file) {
        int chunksStored = ingestionService.ingest(employeeId, checksum, file);
        return new UploadResponse("File Uploaded Successfully", chunksStored);
    }
}
