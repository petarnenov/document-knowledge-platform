package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.server.netty.handler.PipeliningServerHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Exact approved #366 read-only adapter. Neither field names nor artifacts are runtime inputs. */
final class PinnedIngressReadback {
    static final String BINARY_SHA256 = "379303cc7367769872506e85a7d4fce9692b2d04c6aae8bdc9f008f3c51836b4";
    private final Field bodySizeLimits;
    private final Field requestDecompressionEnabled;

    PinnedIngressReadback() {
        try {
            Class<?> type = PipeliningServerHandler.class;
            if (!Modifier.isFinal(type.getModifiers()) || type.getModule().isNamed()
                    || type.getClassLoader() != PinnedIngressReadback.class.getClassLoader()
                    || !artifactMatches(type)) throw new IllegalStateException();
            bodySizeLimits = type.getDeclaredField("bodySizeLimits");
            requestDecompressionEnabled = type.getDeclaredField("requestDecompressionEnabled");
            validateField(bodySizeLimits, type, BodySizeLimits.class);
            validateField(requestDecompressionEnabled, type, boolean.class);
        } catch (Exception failure) {
            throw new IllegalStateException("BFF_INGRESS_POLICY_UNAVAILABLE");
        }
    }

    static void validateField(Field field, Class<?> declaring, Class<?> type) throws IllegalAccessException {
        if (field.getDeclaringClass() != declaring || field.getType() != type
                || field.getModifiers() != Modifier.PRIVATE || !field.trySetAccessible()) {
            throw new IllegalAccessException();
        }
    }

    static boolean artifactMatches(Class<?> type) throws Exception {
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null || !source.getLocation().getProtocol().equals("file")) return false;
        Path path = Path.of(source.getLocation().toURI()).toRealPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.getFileName().toString().endsWith(".jar")) return false;
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] piece = new byte[8192];
            int count;
            while ((count = input.read(piece)) != -1) digest.update(piece, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest()).equals(BINARY_SHA256);
    }

    boolean matches(PipeliningServerHandler actual) {
        if (actual == null || actual.getClass() != PipeliningServerHandler.class) return false;
        try {
            Object value = bodySizeLimits.get(actual);
            return value instanceof BodySizeLimits limits && limits.maxBodySize() == 100000000
                    && limits.maxBufferSize() == 131072 && !requestDecompressionEnabled.getBoolean(actual);
        } catch (IllegalAccessException | RuntimeException failure) {
            return false;
        }
    }
}
