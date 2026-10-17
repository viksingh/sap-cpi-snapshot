package com.sakiv.cpi.snapshot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** In-memory stand-in for the Cloud Integration OData API and its token endpoint. */
public class MockCpiServer implements AutoCloseable {

    public static final String CLIENT_ID = "sb-test";
    public static final String CLIENT_SECRET = "secret";

    /** packageId -> (entitySet -> artifactIds) */
    public final Map<String, Map<String, List<String>>> tenant = new LinkedHashMap<>();
    /** artifactId -> zip file entries */
    public final Map<String, Map<String, String>> content = new ConcurrentHashMap<>();
    /** Package IDs whose artifact listing should fail with HTTP 500. */
    public final Set<String> failingPackages = ConcurrentHashMap.newKeySet();
    public final AtomicInteger tokenRequests = new AtomicInteger();
    /** Page size for IntegrationPackages, to exercise __next paging. */
    public int pageSize = 2;

    private final HttpServer server;

    private static final Pattern LIST_ARTIFACTS =
            Pattern.compile("/api/v1/IntegrationPackages\\('(.+)'\\)/(\\w+)");
    private static final Pattern DOWNLOAD =
            Pattern.compile("/api/v1/(\\w+)\\(Id='(.+)',Version='active'\\)/\\$value");

    public MockCpiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", this::token);
        server.createContext("/api/v1", this::api);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void addArtifact(String pkg, String entitySet, String id, Map<String, String> files) {
        tenant.computeIfAbsent(pkg, k -> new LinkedHashMap<>())
                .computeIfAbsent(entitySet, k -> new ArrayList<>()).add(id);
        content.put(id, files);
    }

    public void addEmptyPackage(String pkg) {
        tenant.computeIfAbsent(pkg, k -> new LinkedHashMap<>());
    }

    private void token(HttpExchange ex) throws IOException {
        tokenRequests.incrementAndGet();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(auth)) {
            send(ex, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        send(ex, 200, "{\"access_token\":\"tok123\",\"expires_in\":3600}");
    }

    private void api(HttpExchange ex) throws IOException {
        if (!"Bearer tok123".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            send(ex, 401, "");
            return;
        }
        String path = URLDecoder.decode(ex.getRequestURI().getRawPath(), StandardCharsets.UTF_8)
                .replace("''", "\u0000");
        String query = ex.getRequestURI().getRawQuery();

        if (path.equals("/api/v1/IntegrationPackages")) {
            int skip = 0;
            if (query != null && query.startsWith("$skiptoken=")) {
                skip = Integer.parseInt(query.substring("$skiptoken=".length()));
            }
            List<String> ids = new ArrayList<>(tenant.keySet());
            int end = Math.min(ids.size(), skip + pageSize);
            StringBuilder sb = new StringBuilder("{\"d\":{\"results\":[");
            for (int i = skip; i < end; i++) {
                if (i > skip) sb.append(',');
                String id = ids.get(i);
                sb.append("{\"__metadata\":{\"uri\":\"x\"},\"Id\":\"").append(id)
                        .append("\",\"Name\":\"").append(id).append(" name\",\"Version\":\"1.0.0\",")
                        .append("\"IntegrationDesigntimeArtifacts\":{\"__deferred\":{}}}");
            }
            sb.append(']');
            if (end < ids.size()) {
                sb.append(",\"__next\":\"IntegrationPackages?$skiptoken=").append(end).append('"');
            }
            sb.append("}}");
            send(ex, 200, sb.toString());
            return;
        }

        Matcher list = LIST_ARTIFACTS.matcher(path);
        if (list.matches()) {
            String pkg = list.group(1).replace("\u0000", "'");
            if (failingPackages.contains(pkg)) {
                send(ex, 500, "boom");
                return;
            }
            List<String> ids = tenant.getOrDefault(pkg, Map.of()).getOrDefault(list.group(2), List.of());
            StringBuilder sb = new StringBuilder("{\"d\":{\"results\":[");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append("{\"Id\":\"").append(ids.get(i)).append("\",\"Name\":\"").append(ids.get(i))
                        .append("\",\"Version\":\"1.0.").append(i).append("\"}");
            }
            send(ex, 200, sb.append("]}}").toString());
            return;
        }

        Matcher dl = DOWNLOAD.matcher(path);
        if (dl.matches()) {
            Map<String, String> files = content.get(dl.group(2).replace("\u0000", "'"));
            if (files == null) {
                send(ex, 404, "");
                return;
            }
            byte[] zip = zip(files);
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.sendResponseHeaders(200, zip.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(zip);
            }
            return;
        }
        send(ex, 404, "no route " + path);
    }

    public static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> f : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(f.getKey()));
                zos.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
