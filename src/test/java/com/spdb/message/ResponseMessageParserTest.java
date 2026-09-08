package com.spdb.message;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class ResponseMessageParserTest {
    private final ResponseMessageParser parser = new ResponseMessageParser();

    @Test
    void findsNamespacedReturnCodeWithoutDependingOnTransactionElementName() {
        String xml = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:s="http://esb.example/services">
                  <soap:Body>
                    <s:RspCorpDepAcntgDtlQry>
                      <s:RspSvcHeader><s:ReturnCode>EGG0674</s:ReturnCode></s:RspSvcHeader>
                    </s:RspCorpDepAcntgDtlQry>
                  </soap:Body>
                </soap:Envelope>
                """;
        assertThat(parser.parseReturnCode("soap", xml.getBytes(StandardCharsets.UTF_8))).isEqualTo("EGG0674");
    }
}
