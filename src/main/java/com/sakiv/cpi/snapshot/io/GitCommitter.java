package com.sakiv.cpi.snapshot.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Commits (and optionally pushes) the snapshot folder using the local git executable. */
public class GitCommitter {

    public enum Result { COMMITTED, NO_CHANGES }

    private final Path repo;

    public GitCommitter(Path repo) {
        this.repo = repo;
    }

    public Result commit(String message, boolean push) throws IOException, InterruptedException {
        String inside = run(false, "rev-parse", "--is-inside-work-tree").trim();
        if (!"true".equals(inside)) {
            throw new IOException(repo + " is not inside a git repository. Run 'git init' there first.");
        }
        run(true, "add", "-A", ".");
        String status = run(true, "status", "--porcelain", "--", ".").trim();
        if (status.isEmpty()) {
            return Result.NO_CHANGES;
        }
        run(true, "commit", "-m", message, "--", ".");
        if (push) {
            run(true, "push");
        }
        return Result.COMMITTED;
    }

    private String run(boolean failOnError, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0 && failOnError) {
            throw new IOException("git " + String.join(" ", args) + " failed (" + code + "): " + out.trim());
        }
        return code == 0 ? out : "";
    }
}
