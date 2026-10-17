package com.sakiv.cpi.snapshot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sakiv.cpi.snapshot.api.ArtifactType;
import com.sakiv.cpi.snapshot.api.CpiClient;
import com.sakiv.cpi.snapshot.io.Files2;
import com.sakiv.cpi.snapshot.io.ZipExtractor;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Writes the tenant's design-time content to a folder:
 *
 * <pre>
 * &lt;out&gt;/snapshot-manifest.json
 * &lt;out&gt;/&lt;PackageId&gt;/package.json
 * &lt;out&gt;/&lt;PackageId&gt;/iflows/&lt;ArtifactId&gt;/...        (unzipped)
 * &lt;out&gt;/&lt;PackageId&gt;/value-mappings/&lt;ArtifactId&gt;/...
 * </pre>
 *
 * Files contain no timestamps of their own, so an unchanged tenant gives an unchanged
 * folder and git sees no diff.
 */
public class SnapshotService {

    public static final String PACKAGE_MARKER = "package.json";
    public static final String MANIFEST = "snapshot-manifest.json";

    /** Package fields kept in package.json; everything else (links, metadata) is dropped. */
    private static final List<String> PACKAGE_FIELDS = List.of(
            "Id", "Name", "Description", "ShortText", "Version", "Vendor", "Mode",
            "SupportedPlatform", "Products", "Keywords", "Countries", "Industries", "LineOfBusiness",
            "CreatedBy", "CreationDate", "ModifiedBy", "ModifiedDate");

    private final CpiClient client;
    private final PrintStream log;
    private final boolean verbose;
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public SnapshotService(CpiClient client, PrintStream log, boolean verbose) {
        this.client = client;
        this.log = log;
        this.verbose = verbose;
    }

    /**
     * @param allowEmpty accept a tenant that returns no packages even though the snapshot
     *                   folder already holds packages (normally treated as an error, since it
     *                   usually means the tenant was reset, deleted or the key points elsewhere)
     */
    public record Options(Path outDir, Pattern include, Pattern exclude, boolean prune, boolean allowEmpty) {
        public Options(Path outDir, Pattern include, Pattern exclude, boolean prune) {
            this(outDir, include, exclude, prune, false);
        }
    }

    /** Thrown when the tenant looks empty but the existing snapshot is not. Nothing is written. */
    public static class EmptyTenantException extends IOException {
        public EmptyTenantException(String message) {
            super(message);
        }
    }

    public record Summary(int packages, int artifacts, List<String> failures, List<String> pruned) {
        public boolean ok() {
            return failures.isEmpty();
        }
    }

    public Summary run(Options opt) throws IOException, InterruptedException {
        Path out = opt.outDir().toAbsolutePath().normalize();
        Files.createDirectories(out);

        List<JsonNode> packages = new ArrayList<>(client.listPackages());
        packages.sort(Comparator.comparing(p -> p.path("Id").asText()));

        if (packages.isEmpty() && !opt.allowEmpty()) {
            List<String> existing = existingPackageFolders(out);
            if (!existing.isEmpty()) {
                throw new EmptyTenantException("Tenant returned no integration packages, but the snapshot has "
                        + existing.size() + " (" + String.join(", ", existing) + "). Nothing was changed. "
                        + "The tenant may have been reset or deleted, or the service key points to another tenant. "
                        + "Use --allow-empty if the tenant really is empty now.");
            }
        }

        List<String> failures = new ArrayList<>();
        Set<String> written = new HashSet<>();
        Set<String> skippedByFilter = new HashSet<>();
        ArrayNode manifest = json.createArrayNode();
        int artifactCount = 0;

        for (JsonNode pkg : packages) {
            String id = pkg.path("Id").asText();
            String folder = Files2.safeName(id);
            if (!selected(id, opt)) {
                skippedByFilter.add(folder);
                continue;
            }
            PackageResult r = snapshotPackage(pkg, out.resolve(folder));
            written.add(folder);
            artifactCount += r.artifacts().size();
            failures.addAll(r.failures());
            manifest.add(manifestEntry(pkg, r));
            log.printf("  %-40s %3d artifact(s)%s%n", id, r.artifacts().size(),
                    r.failures().isEmpty() ? "" : "  [" + r.failures().size() + " failed]");
        }

        List<String> pruned = opt.prune() ? prune(out, written, skippedByFilter) : List.of();
        json.writeValue(out.resolve(MANIFEST).toFile(), manifest);
        return new Summary(written.size(), artifactCount, failures, pruned);
    }

    private record PackageResult(List<ObjectNode> artifacts, List<String> failures) {
    }

    /**
     * Builds the package in a staging folder and swaps it in, so a failed listing never
     * wipes the previous snapshot of that package.
     */
    private PackageResult snapshotPackage(JsonNode pkg, Path target) throws IOException, InterruptedException {
        String id = pkg.path("Id").asText();
        Path staging = target.resolveSibling(target.getFileName() + ".snapshot-tmp");
        Files2.deleteRecursively(staging);
        Files.createDirectories(staging);

        List<ObjectNode> artifacts = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        boolean listingFailed = false;

        for (ArtifactType type : ArtifactType.values()) {
            List<JsonNode> list;
            try {
                list = client.listArtifacts(id, type);
            } catch (IOException e) {
                failures.add(id + " / " + type.entitySet() + ": " + e.getMessage());
                listingFailed = true;
                continue;
            }
            list.sort(Comparator.comparing(a -> a.path("Id").asText()));
            for (JsonNode a : list) {
                String artifactId = a.path("Id").asText();
                Path dir = staging.resolve(type.folder()).resolve(Files2.safeName(artifactId));
                try {
                    byte[] zip = client.downloadArtifact(type, artifactId);
                    int files = ZipExtractor.extract(zip, dir);
                    ObjectNode entry = json.createObjectNode();
                    entry.put("type", type.folder());
                    entry.put("id", artifactId);
                    entry.put("name", a.path("Name").asText(artifactId));
                    entry.put("version", a.path("Version").asText(""));
                    artifacts.add(entry);
                    if (verbose) {
                        log.printf("      %s/%s (%d files)%n", type.folder(), artifactId, files);
                    }
                } catch (IOException e) {
                    failures.add(id + " / " + artifactId + ": " + e.getMessage());
                    Files2.deleteRecursively(dir);
                }
            }
        }

        if (listingFailed && Files.exists(target)) {
            // Keep the previous snapshot rather than replacing it with a partial one.
            Files2.deleteRecursively(staging);
            return new PackageResult(artifacts, failures);
        }

        json.writeValue(staging.resolve(PACKAGE_MARKER).toFile(), packageMetadata(pkg));
        Files2.deleteRecursively(target);
        Files.move(staging, target);
        return new PackageResult(artifacts, failures);
    }

    private ObjectNode packageMetadata(JsonNode pkg) {
        TreeMap<String, JsonNode> fields = new TreeMap<>();
        for (String f : PACKAGE_FIELDS) {
            JsonNode v = pkg.get(f);
            if (v != null && !v.isNull() && !v.isObject()) {
                fields.put(f, v);
            }
        }
        ObjectNode node = json.createObjectNode();
        fields.forEach(node::set);
        return node;
    }

    private ObjectNode manifestEntry(JsonNode pkg, PackageResult r) {
        ObjectNode node = json.createObjectNode();
        node.put("id", pkg.path("Id").asText());
        node.put("name", pkg.path("Name").asText());
        node.put("version", pkg.path("Version").asText(""));
        ArrayNode list = node.putArray("artifacts");
        r.artifacts().forEach(list::add);
        return node;
    }

    /** Removes package folders this tool wrote earlier whose package no longer exists on the tenant. */
    private List<String> prune(Path out, Set<String> keep, Set<String> skippedByFilter) throws IOException {
        List<String> removed = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(out, Files::isDirectory)) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                if (name.endsWith(".snapshot-tmp")) {
                    Files2.deleteRecursively(dir);
                    continue;
                }
                boolean ours = Files.isRegularFile(dir.resolve(PACKAGE_MARKER));
                if (ours && !keep.contains(name) && !skippedByFilter.contains(name)) {
                    Files2.deleteRecursively(dir);
                    removed.add(name);
                }
            }
        }
        removed.sort(String::compareTo);
        return removed;
    }

    private static List<String> existingPackageFolders(Path out) throws IOException {
        List<String> found = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(out, Files::isDirectory)) {
            for (Path dir : dirs) {
                if (Files.isRegularFile(dir.resolve(PACKAGE_MARKER))) {
                    found.add(dir.getFileName().toString());
                }
            }
        }
        found.sort(String::compareTo);
        return found;
    }

    private static boolean selected(String id, Options opt) {
        if (opt.include() != null && !opt.include().matcher(id).matches()) return false;
        return opt.exclude() == null || !opt.exclude().matcher(id).matches();
    }
}
