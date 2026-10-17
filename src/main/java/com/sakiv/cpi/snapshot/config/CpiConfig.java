package com.sakiv.cpi.snapshot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Connection settings for the Cloud Integration OData API.
 *
 * <p>Resolution order for each value: explicit CLI option, then environment variable,
 * then the service key file. The service key may be the plain BTP service key JSON
 * (with an {@code oauth} block) or a flat object with the same field names.</p>
 */
public record CpiConfig(String apiUrl, String tokenUrl, String clientId, String clientSecret) {

    public static final String ENV_URL = "CPI_URL";
    public static final String ENV_TOKEN_URL = "CPI_TOKEN_URL";
    public static final String ENV_CLIENT_ID = "CPI_CLIENT_ID";
    public static final String ENV_CLIENT_SECRET = "CPI_CLIENT_SECRET";

    public static CpiConfig resolve(String url, String tokenUrl, String clientId, String clientSecret,
                                    Path serviceKey, Map<String, String> env) throws IOException {
        JsonNode key = null;
        if (serviceKey != null) {
            if (!Files.isRegularFile(serviceKey)) {
                throw new IllegalArgumentException("Service key file not found: " + serviceKey);
            }
            JsonNode root = new ObjectMapper().readTree(serviceKey.toFile());
            key = root.has("oauth") ? root.get("oauth") : root;
        }

        String u = first(url, env.get(ENV_URL), text(key, "url"));
        String t = first(tokenUrl, env.get(ENV_TOKEN_URL), text(key, "tokenurl"));
        String id = first(clientId, env.get(ENV_CLIENT_ID), text(key, "clientid"));
        String secret = first(clientSecret, env.get(ENV_CLIENT_SECRET), text(key, "clientsecret"));

        StringBuilder missing = new StringBuilder();
        if (u == null) missing.append(" url");
        if (t == null) missing.append(" tokenurl");
        if (id == null) missing.append(" clientid");
        if (secret == null) missing.append(" clientsecret");
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing connection settings:" + missing
                    + ". Pass --service-key, set " + ENV_URL + "/" + ENV_TOKEN_URL + "/"
                    + ENV_CLIENT_ID + "/" + ENV_CLIENT_SECRET + ", or use the options.");
        }
        return new CpiConfig(normaliseApiUrl(u), normaliseTokenUrl(t), id, secret);
    }

    static String normaliseApiUrl(String url) {
        String u = withScheme(url.trim());
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/api/v1")) u = u.substring(0, u.length() - "/api/v1".length());
        return u;
    }

    static String normaliseTokenUrl(String url) {
        String u = withScheme(url.trim());
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u.endsWith("/oauth/token") ? u : u + "/oauth/token";
    }

    private static String withScheme(String u) {
        return u.startsWith("http://") || u.startsWith("https://") ? u : "https://" + u;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return v.isBlank() ? null : v;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    @Override
    public String toString() {
        return "CpiConfig[apiUrl=" + apiUrl + ", tokenUrl=" + tokenUrl + ", clientId=" + clientId + ", clientSecret=***]";
    }
}
