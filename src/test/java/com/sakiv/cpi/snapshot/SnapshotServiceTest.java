package com.sakiv.cpi.snapshot;

import com.sakiv.cpi.snapshot.api.CpiClient;
import com.sakiv.cpi.snapshot.config.CpiConfig;
import com.sakiv.cpi.snapshot.io.ZipExtractor;
import com.sakiv.cpi.snapshot.service.SnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotServiceTest {

    @TempDir
    Path out;

    MockCpiServer mock;
    CpiClient client;

    @BeforeEach
    void setUp() throws IOException {
        mock = new MockCpiServer();
        mock.addArtifact("CertAuthPoC", "IntegrationDesigntimeArtifacts", "Cert_HTTP_Inbound",
                Map.of("META-INF/MANIFEST.MF", "Bundle-SymbolicName: Cert_HTTP_Inbound",
                        "src/main/resources/scenarioflows/integrationflow/Cert_HTTP_Inbound.iflw", "<bpmn/>"));
        mock.addArtifact("CertAuthPoC", "ScriptCollectionDesigntimeArtifacts", "CertScripts",
                Map.of("src/main/resources/script/log.groovy", "println 'hi'"));
        mock.addArtifact("EvolveCutoverXIReprocess", "IntegrationDesigntimeArtifacts", "XI_Reprocess",
                Map.of("src/main/resources/parameters.prop", "receiver=ecc"));
        mock.addArtifact("TEST_IFLOWS", "ValueMappingDesigntimeArtifacts", "VM_Plants",
                Map.of("value_mapping.xml", "<vm/>"));
        mock.addEmptyPackage("Empty.Pkg");
        client = new CpiClient(new CpiConfig(mock.baseUrl(), mock.baseUrl() + "/oauth/token",
                MockCpiServer.CLIENT_ID, MockCpiServer.CLIENT_SECRET)).withRetryBaseMillis(5);
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    private SnapshotService.Summary run(boolean prune) throws Exception {
        return run(prune, null, null);
    }

    private SnapshotService.Summary run(boolean prune, Pattern include, Pattern exclude) throws Exception {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
        return new SnapshotService(client, quiet, false)
                .run(new SnapshotService.Options(out, include, exclude, prune));
    }

    @Test
    void writesPackagesAndUnzippedArtifacts() throws Exception {
        SnapshotService.Summary s = run(true);

        assertTrue(s.ok(), () -> "failures: " + s.failures());
        assertEquals(4, s.packages(), "paging should return all 4 packages");
        assertEquals(4, s.artifacts());
        assertTrue(Files.isRegularFile(out.resolve(
                "CertAuthPoC/iflows/Cert_HTTP_Inbound/src/main/resources/scenarioflows/integrationflow/Cert_HTTP_Inbound.iflw")));
        assertEquals("println 'hi'", Files.readString(out.resolve(
                "CertAuthPoC/script-collections/CertScripts/src/main/resources/script/log.groovy")));
        assertTrue(Files.isRegularFile(out.resolve("TEST_IFLOWS/value-mappings/VM_Plants/value_mapping.xml")));
        assertTrue(Files.isRegularFile(out.resolve("Empty.Pkg/package.json")));

        String pkgJson = Files.readString(out.resolve("CertAuthPoC/package.json"));
        assertTrue(pkgJson.contains("\"Id\" : \"CertAuthPoC\""));
        assertFalse(pkgJson.contains("__metadata"), "OData metadata must be stripped");
        assertFalse(pkgJson.contains("__deferred"), "deferred navigation links must be stripped");
        assertEquals(1, mock.tokenRequests.get(), "token should be cached across calls");
    }

    @Test
    void secondRunIsByteIdentical() throws Exception {
        run(true);
        String manifest1 = Files.readString(out.resolve(SnapshotService.MANIFEST));
        String pkg1 = Files.readString(out.resolve("CertAuthPoC/package.json"));
        run(true);
        assertEquals(manifest1, Files.readString(out.resolve(SnapshotService.MANIFEST)));
        assertEquals(pkg1, Files.readString(out.resolve("CertAuthPoC/package.json")));
    }

    @Test
    void prunesDeletedPackagesButLeavesOtherFolders() throws Exception {
        run(true);
        Files.createDirectories(out.resolve(".github/workflows"));
        Files.writeString(out.resolve("README.md"), "keep me");

        mock.tenant.remove("TEST_IFLOWS");
        SnapshotService.Summary s = run(true);

        assertEquals(java.util.List.of("TEST_IFLOWS"), s.pruned());
        assertFalse(Files.exists(out.resolve("TEST_IFLOWS")));
        assertTrue(Files.exists(out.resolve(".github/workflows")));
        assertEquals("keep me", Files.readString(out.resolve("README.md")));
    }

    @Test
    void noPruneKeepsDeletedPackages() throws Exception {
        run(true);
        mock.tenant.remove("TEST_IFLOWS");
        run(false);
        assertTrue(Files.exists(out.resolve("TEST_IFLOWS/package.json")));
    }

    @Test
    void removesArtifactDeletedFromPackage() throws Exception {
        run(true);
        mock.tenant.get("CertAuthPoC").remove("ScriptCollectionDesigntimeArtifacts");
        run(true);
        assertFalse(Files.exists(out.resolve("CertAuthPoC/script-collections")));
        assertTrue(Files.exists(out.resolve("CertAuthPoC/iflows/Cert_HTTP_Inbound")));
    }

    @Test
    void failedListingKeepsPreviousSnapshotOfThatPackage() throws Exception {
        run(true);
        mock.failingPackages.add("CertAuthPoC");
        SnapshotService.Summary s = run(true);

        assertFalse(s.ok());
        assertTrue(s.failures().stream().allMatch(f -> f.startsWith("CertAuthPoC")));
        assertTrue(Files.exists(out.resolve("CertAuthPoC/iflows/Cert_HTTP_Inbound")),
                "previous snapshot must survive a failed listing");
        assertTrue(Files.exists(out.resolve("EvolveCutoverXIReprocess/iflows/XI_Reprocess")));
        try (var s2 = Files.list(out)) {
            assertTrue(s2.noneMatch(p -> p.getFileName().toString().endsWith(".snapshot-tmp")));
        }
    }

    @Test
    void includeAndExcludeFilters() throws Exception {
        SnapshotService.Summary s = run(true, Pattern.compile("C.*|Evolve.*"), Pattern.compile("Evolve.*"));
        assertEquals(1, s.packages());
        assertTrue(Files.exists(out.resolve("CertAuthPoC")));
        assertFalse(Files.exists(out.resolve("EvolveCutoverXIReprocess")));
    }

    @Test
    void filteredRunDoesNotPruneOtherPackages() throws Exception {
        run(true);
        run(true, Pattern.compile("CertAuthPoC"), null);
        assertTrue(Files.exists(out.resolve("TEST_IFLOWS/package.json")));
    }

    @Test
    void zipSlipIsRejected(@TempDir Path target) throws Exception {
        byte[] evil = MockCpiServer.zip(Map.of("../../escape.txt", "x"));
        IOException e = assertThrows(IOException.class, () -> ZipExtractor.extract(evil, target));
        assertTrue(e.getMessage().contains("Unsafe"));
    }

    @Test
    void badCredentialsGiveClearError() {
        CpiClient bad = new CpiClient(new CpiConfig(mock.baseUrl(), mock.baseUrl() + "/oauth/token", "x", "y"));
        IOException e = assertThrows(IOException.class, bad::listPackages);
        assertTrue(e.getMessage().contains("Token request failed"));
    }
}
