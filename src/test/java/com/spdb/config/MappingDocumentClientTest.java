package com.spdb.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MappingDocumentClientTest {

    @Test
    void buildsMultiCodeDownloadUriWithCommaSeparatedCodesAndMapping9Suffix() {
        MappingDocumentClient client = new MappingDocumentClient(
                RestClient.builder().build(),
                new MappingDocumentProperties(
                        "http://localhost:8888/serviceGovOut/OutInterface/downloadMappingFile.action?param=multi,{codes},MAPPING9",
                        10
                )
        );

        String uri = client.downloadUri(List.of("C587", "C170", "C025"));

        assertThat(uri).isEqualTo("http://localhost:8888/serviceGovOut/OutInterface/downloadMappingFile.action?param=multi,C587,C170,C025,MAPPING9");
    }

    @Test
    void partitionsCodesIntoBatchesOfAtMostConfiguredSize() {
        MappingDocumentClient client = new MappingDocumentClient(
                RestClient.builder().build(),
                new MappingDocumentProperties("http://server/download?param=multi,{codes},MAPPING9", 3)
        );

        List<List<String>> batches = client.partitionCodes(List.of("A001", "A002", "A003", "A004", "A005", "A006", "A007"));

        assertThat(batches).containsExactly(
                List.of("A001", "A002", "A003"),
                List.of("A004", "A005", "A006"),
                List.of("A007")
        );
    }
}
