package com.sakiv.cpi.snapshot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakiv.cpi.snapshot.config.CpiConfig;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Thin read-only client for the Cloud Integration OData API (/api/v1). */
public class CpiClient {

    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http;
    private final TokenProvider tokens;
    private final String apiBase;
    private final ObjectMapper json = new ObjectMapper();
    private long retryBaseMillis = 1000;

    public CpiClient(CpiConfig config) {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), config);
    }

    public CpiClient(HttpClient http, CpiConfig config) {
        this.http = http;
        this.tokens = new TokenProvider(http, config);
        this.apiBase = config.apiUrl() + "/api/v1";
    }

    /** Shortens retry back-off; used by tests. */
    public CpiClient withRetryBaseMillis(long millis) {
        this.retryBaseMillis = millis;
        return this;
    }

    public List<JsonNode> listPackages() throws IOException, InterruptedException {
        return getCollection(apiBase + "/IntegrationPackages");
    }

    public List<JsonNode> listArtifacts(String packageId, ArtifactType type) throws IOException, InterruptedException {
        return getCollection(apiBase + "/IntegrationPackages(" + key(packageId) + ")/" + type.entitySet());
    }

    public byte[] downloadArtifact(ArtifactType type, String artifactId) throws IOException, InterruptedException {
        String url = apiBase + "/" + type.entitySet()
                + "(Id=" + key(artifactId) + ",Version=" + key("active") + ")/$value";
        HttpResponse<byte[]> response = send(url, "application/zip", HttpResponse.BodyHandlers.ofByteArray());
        return response.body();
    }

    /** Reads an OData V2 collection, following __next links for server-side paging. */
    List<JsonNode> getCollection(String url) throws IOException, InterruptedException {
        List<JsonNode> all = new ArrayList<>();
        String next = url;
        while (next != null) {
            HttpResponse<String> response = send(next, "application/json", HttpResponse.BodyHandlers.ofString());
            JsonNode d = json.readTree(response.body()).path("d");
            JsonNode results = d.has("results") ? d.get("results") : d;
            if (results.isArray()) {
                results.forEach(all::add);
            }
            next = d.hasNonNull("__next") ? absolute(d.get("__next").asText()) : null;
        }
        return all;
    }

    private <T> HttpResponse<T> send(String url, String accept, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + tokens.get())
                    .header("Accept", accept)
                    .GET()
                    .build();
            HttpResponse<T> response = http.send(request, handler);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return response;
            }
            if (status == 401 && attempt < MAX_ATTEMPTS) {
                tokens.invalidate();
                continue;
            }
            if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                last = new CpiApiException("HTTP " + status + " from " + url, status);
                Thread.sleep(retryBaseMillis * attempt * attempt);
                continue;
            }
            throw new CpiApiException("HTTP " + status + " from " + url + hint(status), status);
        }
        throw last != null ? last : new CpiApiException("Request failed: " + url);
    }

    private static String hint(int status) {
        return switch (status) {
            case 401 -> " (unauthorised: check the service key)";
            case 403 -> " (forbidden: the service key needs the WorkspacePackagesRead role)";
            case 404 -> " (not found)";
            default -> "";
        };
    }

    private String absolute(String next) {
        if (next.startsWith("http://") || next.startsWith("https://")) return next;
        if (next.startsWith("/")) return apiBase.substring(0, apiBase.indexOf("/api/v1")) + next;
        return apiBase + "/" + next;
    }

    /** OData key literal: single quotes doubled, then URL-encoded. */
    static String key(String value) {
        String literal = "'" + value.replace("'", "''") + "'";
        return URLEncoder.encode(literal, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
