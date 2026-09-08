package com.spdb.message;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public class HttpRequestMessageSender {
    private final HttpClient client;

    public HttpRequestMessageSender() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    HttpRequestMessageSender(HttpClient client) {
        this.client = client;
    }

    public HttpResponse<byte[]> send(String address, String type, byte[] body, String micServId, String authContent, int timeoutSeconds) throws Exception {
        String url = normalizeUrl(address);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("micServId", micServId == null ? "" : micServId).header("authContent", authContent == null ? "" : authContent)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private String normalizeUrl(String address) {
        String url = address == null ? "" : address.trim();
        if (url.isEmpty()) throw new IllegalArgumentException("服务地址为空");
        if (!url.regionMatches(true, 0, "http://", 0, 7) && !url.regionMatches(true, 0, "https://", 0, 8)) {
            url = "http://" + url;
        }
        return url;
    }
}
