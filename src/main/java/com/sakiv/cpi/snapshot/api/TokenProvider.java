package com.sakiv.cpi.snapshot.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sakiv.cpi.snapshot.config.CpiConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** OAuth 2.0 client-credentials token, cached until shortly before it expires. */
public class TokenProvider {

    private final HttpClient http;
    private final CpiConfig config;
    private final ObjectMapper json = new ObjectMapper();

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public TokenProvider(HttpClient http, CpiConfig config) {
        this.http = http;
        this.config = config;
    }

    public synchronized String get() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(expiresAt)) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString(
                (config.clientId() + ":" + config.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.tokenUrl()))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new CpiApiException("Token request failed with HTTP " + response.statusCode()
                    + " from " + config.tokenUrl() + ". Check the client ID/secret and token URL.");
        }
        JsonNode body = json.readTree(response.body());
        if (!body.hasNonNull("access_token")) {
            throw new CpiApiException("Token response did not contain access_token");
        }
        token = body.get("access_token").asText();
        long ttl = body.path("expires_in").asLong(3600);
        expiresAt = Instant.now().plusSeconds(Math.max(30, ttl - 60));
        return token;
    }

    /** Forces a new token on the next call, e.g. after a 401. */
    public synchronized void invalidate() {
        token = null;
        expiresAt = Instant.EPOCH;
    }
}
