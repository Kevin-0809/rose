package com.spdb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rose.mapping-doc")
public record MappingDocumentProperties(
        String urlTemplate,
        int batchSize
) {
    public MappingDocumentProperties {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            urlTemplate = "http://localhost:8888/serviceGovOut/OutInterface/downloadMappingFile.action?param=multi,{codes},MAPPING9";
        }
        if (batchSize <= 0 || batchSize > 10) {
            batchSize = 10;
        }
    }
}
