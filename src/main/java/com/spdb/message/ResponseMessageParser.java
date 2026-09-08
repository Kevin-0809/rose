package com.spdb.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;

public class ResponseMessageParser {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Charset GBK = Charset.forName("GBK");
    public String parseReturnCode(String type, byte[] body) {
        if (body == null) return "";
        String t = type == null ? "" : type.trim().toLowerCase();
        try {
            if (t.equals("sop") || t.equals("sop2cbsp")) {
                if (body.length < 97) return "";
                return new String(body, 90, 7, GBK).trim();
            }
            if (t.equals("json") || t.equals("bzjson")) {
                JsonNode root = JSON.readTree(body);
                if (t.equals("json")) {
                    JsonNode nested = root.path("Body").path("RspSvcHeader").path("ReturnCode");
                    if (!nested.isMissingNode() && !nested.isNull()) return nested.asText();
                }
                JsonNode node = root.get("ReturnCode");
                return node == null || node.isNull() ? "" : node.asText();
            }
            if (t.equals("soap")) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setExpandEntityReferences(false);
                var doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
                NodeList nodes = doc.getElementsByTagNameNS("*", "ReturnCode");
                if (nodes.getLength() == 0) nodes = doc.getElementsByTagName("ReturnCode");
                return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
            }
        } catch (Exception ignored) { }
        return "";
    }
}
