package com.sakiv.cpi.snapshot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CpiConfigTest {

    @Test
    void readsNestedBtpServiceKey(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("key.json");
        Files.writeString(key, """
                {"oauth":{"url":"https://abc.it-cpitrial05.cfapps.us10-001.hana.ondemand.com",
                          "tokenurl":"https://abc.authentication.us10.hana.ondemand.com/oauth/token",
                          "clientid":"sb-1","clientsecret":"s"}}
                """);
        CpiConfig c = CpiConfig.resolve(null, null, null, null, key, Map.of());
        assertEquals("https://abc.it-cpitrial05.cfapps.us10-001.hana.ondemand.com", c.apiUrl());
        assertEquals("https://abc.authentication.us10.hana.ondemand.com/oauth/token", c.tokenUrl());
        assertEquals("sb-1", c.clientId());
    }

    @Test
    void optionsBeatEnvBeatKey(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("key.json");
        Files.writeString(key, "{\"url\":\"key-host\",\"tokenurl\":\"key-auth\",\"clientid\":\"k\",\"clientsecret\":\"k\"}");
        CpiConfig c = CpiConfig.resolve("opt-host/api/v1/", null, null, null, key,
                Map.of(CpiConfig.ENV_TOKEN_URL, "env-auth", CpiConfig.ENV_CLIENT_ID, "e"));
        assertEquals("https://opt-host", c.apiUrl());
        assertEquals("https://env-auth/oauth/token", c.tokenUrl());
        assertEquals("e", c.clientId());
        assertEquals("k", c.clientSecret());
    }

    @Test
    void missingValuesAreListed() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CpiConfig.resolve("h", null, null, null, null, Map.of()));
        assertTrue(e.getMessage().contains("tokenurl"));
        assertTrue(e.getMessage().contains("clientsecret"));
    }

    @Test
    void secretNotInToString() {
        assertFalse(new CpiConfig("u", "t", "id", "topsecret").toString().contains("topsecret"));
    }
}
