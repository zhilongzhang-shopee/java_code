package org.example.java_code.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@NoArgsConstructor
@Data
public class GenerationRequest {

    private ConfigDTO config;
    private InputDTO input;

    @NoArgsConstructor
    @Data
    public static class ConfigDTO {
        private MetadataDTO metadata;

        @NoArgsConstructor
        @Data
        public static class MetadataDTO {
            private String reg;
            @JsonProperty("user_email")
            private String userEmail;
        }
    }

    @NoArgsConstructor
    @Data
    public static class InputDTO {
        @JsonProperty("raw_docs")
        private List<RawDocsDTO> rawDocs;
        @JsonProperty("table_group_id")
        private String tableGroupId;

        @NoArgsConstructor
        @Data
        public static class RawDocsDTO {
            @JsonProperty("index_info")
            private String indexInfo;
            @JsonProperty("text_content")
            private String textContent;
            private String title;
        }
    }
}

