package com.sakiv.cpi.snapshot.cli;

import com.sakiv.cpi.snapshot.api.CpiClient;
import com.sakiv.cpi.snapshot.config.CpiConfig;
import com.sakiv.cpi.snapshot.io.GitCommitter;
import com.sakiv.cpi.snapshot.service.SnapshotService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@Command(name = "snapshot", mixinStandardHelpOptions = true,
        description = "Download every design-time artifact, unzip it into OUT_DIR and optionally commit to git.")
public class SnapshotCommand implements Callable<Integer> {

    @Mixin
    ConnectionOptions connection;

    @Option(names = {"-o", "--out"}, required = true, paramLabel = "OUT_DIR",
            description = "Target folder, usually a git working tree (or a sub-folder of one).")
    Path outDir;

    @Option(names = "--include", paramLabel = "REGEX",
            description = "Only packages whose ID matches this regex.")
    Pattern include;

    @Option(names = "--exclude", paramLabel = "REGEX",
            description = "Skip packages whose ID matches this regex.")
    Pattern exclude;

    @Option(names = "--prune", negatable = true, defaultValue = "true", fallbackValue = "true",
            description = "Remove package folders that no longer exist on the tenant (default: ${DEFAULT-VALUE}).")
    boolean prune;

    @Option(names = "--commit", description = "git add + commit OUT_DIR when something changed.")
    boolean commit;

    @Option(names = {"-m", "--message"}, paramLabel = "TEXT",
            description = "Commit message (default: 'CPI snapshot <timestamp>').")
    String message;

    @Option(names = "--push", description = "git push after committing (implies --commit).")
    boolean push;

    @Option(names = {"-v", "--verbose"}, description = "Print every artifact.")
    boolean verbose;

    @Override
    public Integer call() throws Exception {
        CpiConfig config = connection.toConfig();
        System.out.println("Tenant : " + config.apiUrl());
        System.out.println("Target : " + outDir.toAbsolutePath().normalize());

        SnapshotService service = new SnapshotService(new CpiClient(config), System.out, verbose);
        SnapshotService.Summary s = service.run(new SnapshotService.Options(outDir, include, exclude, prune));

        System.out.printf("%nPackages: %d, artifacts: %d%n", s.packages(), s.artifacts());
        if (!s.pruned().isEmpty()) {
            System.out.println("Removed (no longer on tenant): " + String.join(", ", s.pruned()));
        }
        if (!s.ok()) {
            System.err.println("\nFailures:");
            s.failures().forEach(f -> System.err.println("  - " + f));
        }

        if (commit || push) {
            String msg = message != null ? message
                    : "CPI snapshot " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"));
            GitCommitter.Result r = new GitCommitter(outDir.toAbsolutePath().normalize()).commit(msg, push);
            System.out.println(r == GitCommitter.Result.NO_CHANGES
                    ? "Git: no changes since last snapshot"
                    : "Git: committed" + (push ? " and pushed" : ""));
        }
        return s.ok() ? 0 : 1;
    }
}
