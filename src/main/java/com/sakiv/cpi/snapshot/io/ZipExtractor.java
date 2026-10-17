package com.sakiv.cpi.snapshot.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Extracts artifact ZIPs, refusing entries that would escape the target folder. */
public final class ZipExtractor {

    private ZipExtractor() {
    }

    /** @return number of files written */
    public static int extract(byte[] zip, Path target) throws IOException {
        Files.createDirectories(target);
        Path root = target.toAbsolutePath().normalize();
        int files = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("Unsafe ZIP entry outside target folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    files++;
                }
            }
        }
        if (files == 0) {
            throw new IOException("Download was not a ZIP or was empty");
        }
        return files;
    }
}
