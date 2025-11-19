package org.example.java_code.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Data
public class ApiResponse {

    private OutputDTO output;
    private MetadataDTO metadata;

    @NoArgsConstructor
    @Data
    public static class OutputDTO {
        private String introduction;
    }

    @NoArgsConstructor
    @Data
    public static class MetadataDTO {
        private String runId;
        private java.util.List<?> feedbackTokens;
    }
}

