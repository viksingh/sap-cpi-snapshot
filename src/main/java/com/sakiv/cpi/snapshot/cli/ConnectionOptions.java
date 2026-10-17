package com.sakiv.cpi.snapshot.cli;

import com.sakiv.cpi.snapshot.config.CpiConfig;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Path;

/** Connection options shared by all commands. */
public class ConnectionOptions {

    @Option(names = {"-k", "--service-key"}, paramLabel = "FILE",
            description = "BTP service key JSON for the Process Integration Runtime 'api' plan.")
    Path serviceKey;

    @Option(names = "--url", paramLabel = "URL",
            description = "Tenant API URL (overrides service key; env CPI_URL).")
    String url;

    @Option(names = "--token-url", paramLabel = "URL",
            description = "OAuth token URL (env CPI_TOKEN_URL).")
    String tokenUrl;

    @Option(names = "--client-id", paramLabel = "ID",
            description = "OAuth client ID (env CPI_CLIENT_ID).")
    String clientId;

    @Option(names = "--client-secret", paramLabel = "SECRET",
            description = "OAuth client secret (env CPI_CLIENT_SECRET). Prefer the env variable.")
    String clientSecret;

    public CpiConfig toConfig() throws IOException {
        return CpiConfig.resolve(url, tokenUrl, clientId, clientSecret, serviceKey, System.getenv());
    }
}
