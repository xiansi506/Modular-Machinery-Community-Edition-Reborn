package com.reborn.modularmachinery.selection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** UTF-8 fragments, with loader-safe names and no replacement of an existing export. */
public final class MachineFragmentWriter {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
    private MachineFragmentWriter() {}

    public static String fileNameFor(String playerName, LocalDateTime timestamp) {
        String safeName = playerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return "machine-" + safeName + "-" + TIMESTAMP.format(timestamp) + ".json";
    }

    public static Path write(Path directory, String fileName, String json) throws IOException {
        if (!fileName.matches("[a-z0-9_.-]+\\.(json|js)")) {
            throw new IOException("Invalid export file name: " + fileName);
        }
        Files.createDirectories(directory);
        for (int suffix = 0; ; suffix++) {
            String name = suffix == 0 ? fileName : fileName.substring(0, fileName.lastIndexOf('.')) + "_" + suffix + fileName.substring(fileName.lastIndexOf('.'));
            Path target = directory.resolve(name);
            java.io.OutputStream stream;
            try {
                stream = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException collision) {
                continue;
            }
            try (stream) {
                stream.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                try { Files.deleteIfExists(target); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
            return target;
        }
    }
}
