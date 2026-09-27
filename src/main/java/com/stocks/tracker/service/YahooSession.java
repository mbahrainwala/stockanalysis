package com.stocks.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared access to Yahoo Finance endpoints that need a session cookie and "crumb" token
 * (quote batches, analyst data, screeners). The crumb is fetched once and reused; a rejected
 * one is refreshed automatically.
 */
@Service
public class YahooSession {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";
    private static final long CRUMB_MS = Duration.ofHours(6).toMillis();

    private final HttpClient http = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private String crumb;
    private long crumbAt;

    /**
     * GETs a JSON document. When {@code withCrumb} is set the crumb is appended as a query parameter.
     * Returns null for a non-success status other than an auth failure (e.g. 404 for an unknown symbol).
     */
    public JsonNode getJson(String url, boolean withCrumb) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String target = url;
            if (withCrumb) {
                target += (url.contains("?") ? "&" : "?") + "crumb=" + java.net.URLEncoder.encode(crumb(attempt > 0),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            HttpResponse<String> r = http.send(request(target), HttpResponse.BodyHandlers.ofString());
            if (withCrumb && (r.statusCode() == 401 || r.statusCode() == 403)) {
                continue; // stale crumb: fetch a new one and retry once
            }
            return r.statusCode() / 100 == 2 ? mapper.readTree(r.body()) : null;
        }
        return null;
    }

    private synchronized String crumb(boolean force) throws IOException, InterruptedException {
        if (!force && crumb != null && System.currentTimeMillis() - crumbAt < CRUMB_MS) {
            return crumb;
        }
        // The first request just plants the session cookie (its own status is irrelevant).
        http.send(request("https://fc.yahoo.com"), HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> r = http.send(request("https://query1.finance.yahoo.com/v1/test/getcrumb"),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2 || r.body().isBlank() || r.body().contains("<")) {
            throw new IOException("Could not obtain a Yahoo session (HTTP " + r.statusCode() + ")");
        }
        crumb = r.body().trim();
        crumbAt = System.currentTimeMillis();
        return crumb;
    }

    private static HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).header("Accept", "application/json,*/*")
                .timeout(Duration.ofSeconds(10)).GET().build();
    }
}
