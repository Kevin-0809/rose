package com.spdb.config;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

@Component
public class MappingDocumentClient {
    private final RestClient restClient;
    private final MappingDocumentProperties properties;

    @Autowired
    public MappingDocumentClient(RestClient.Builder restClientBuilder, MappingDocumentProperties properties) {
        this(restClientBuilder.build(), properties);
    }

    MappingDocumentClient(RestClient restClient, MappingDocumentProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public byte[] download(List<String> tranCodes) {
        return restClient.get()
                .uri(downloadUri(tranCodes))
                .retrieve()
                .body(byte[].class);
    }

    String downloadUri(List<String> tranCodes) {
        return properties.urlTemplate().replace("{codes}", String.join(",", tranCodes));
    }

    List<List<String>> partitionCodes(List<String> tranCodes) {
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < tranCodes.size(); i += properties.batchSize()) {
            batches.add(tranCodes.subList(i, Math.min(i + properties.batchSize(), tranCodes.size())));
        }
        return batches;
    }
}
