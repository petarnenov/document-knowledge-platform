package org.documentknowledge.devlogging.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import org.documentknowledge.devlogging.domain.VerifiedLogManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PrivateManifestCommandTest {
    @TempDir Path directory;

    String[] arguments() throws Exception {
        byte[] bytes = PrivateLogManifest.canonical(PrivateLogManifestTest.manifest(PrivateLogManifestTest.vocabulary()));
        Path manifest = directory.resolve("manifest.json"), password = directory.resolve("password");
        Files.write(manifest, bytes); Files.writeString(password, "s".repeat(64));
        for (Path file : List.of(manifest, password))
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return new String[]{"install-manifest", "--manifest", manifest.toString(), "--sha256",
            VerifiedLogManifest.sha256(bytes), "--jdbc-url", "jdbc:postgresql://logging-db:5432/dkp_dev_logs",
            "--password-file", password.toString()};
    }

    static String failure(String[] args) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            assertEquals(1, PrivateManifestCommand.run(args, output));
        }
        String result = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(result.matches("LOG_MANIFEST_INSTALL_(INVALID|UNAVAILABLE)\\R"), result);
        assertFalse(result.contains("ssssssss")); assertFalse(result.contains("Exception"));
        return result.strip();
    }

    @Test void closedCommandShapeRejectsMissingExtraReorderedAndUnknownArguments() throws Exception {
        assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(null));
        assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(new String[0]));
        for (int index : List.of(0, 1, 3, 5, 7)) {
            var args = arguments(); args[index] = "unrecognized";
            assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(args));
        }
        assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(Arrays.copyOf(arguments(), 10)));
    }

    @Test void dedicatedDatabaseAndFinitePortRejectAlternateAuthorityAndJdbcOptions() throws Exception {
        for (String url : List.of("jdbc:postgresql://logging-db:5432/business", "jdbc:postgresql://logging-db:0/dkp_dev_logs",
                "jdbc:postgresql://logging-db:65536/dkp_dev_logs", "jdbc:postgresql://logging-db:5432/dkp_dev_logs?user=postgres",
                "jdbc:postgresql://user:password@logging-db:5432/dkp_dev_logs")) {
            var args = arguments(); args[6] = url;
            assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(args));
        }
        var args = arguments(); args[6] = null;
        assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(args));
    }

    @Test void privateManifestDigestAndPasswordBoundsFailBeforeConnecting() throws Exception {
        var args = arguments(); args[4] = "0".repeat(64);
        assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(args));
        for (String password : List.of("short", "invalid password".repeat(3))) {
            args = arguments(); Files.writeString(Path.of(args[8]), password);
            assertEquals("LOG_MANIFEST_INSTALL_INVALID", failure(args));
        }
        args = arguments(); Files.setPosixFilePermissions(Path.of(args[8]), PosixFilePermissions.fromString("rw-r-----"));
        assertEquals("LOG_MANIFEST_INSTALL_UNAVAILABLE", failure(args));
        args = arguments(); Path link = directory.resolve("password-link");
        Files.createSymbolicLink(link, Path.of(args[8])); args[8] = link.toString();
        assertEquals("LOG_MANIFEST_INSTALL_UNAVAILABLE", failure(args));
    }
}
