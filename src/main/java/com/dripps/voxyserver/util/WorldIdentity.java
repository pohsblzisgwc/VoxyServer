package com.dripps.voxyserver.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** A namespace, not a revision: restoring this file never suppresses content validation. */
public final class WorldIdentity {
    private WorldIdentity() {}
    public static UUID loadOrCreate(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            try {
                Files.writeString(file, UUID.randomUUID().toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                // Another opener has installed the identity.
            }
        }
        try {
            return UUID.fromString(Files.readString(file, StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid LOD world identity: " + file, invalid);
        }
    }
}
