package com.sakiv.cpi.snapshot.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Small file-system helpers. */
public final class Files2 {

    private Files2() {
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Folder-safe version of an artifact or package ID. */
    public static String safeName(String id) {
        String s = id.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "_" + s;
        return s;
    }
}
