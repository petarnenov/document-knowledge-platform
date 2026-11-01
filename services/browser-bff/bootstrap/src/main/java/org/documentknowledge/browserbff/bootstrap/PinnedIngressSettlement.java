package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.http.body.ByteBody;
import io.micronaut.http.body.stream.AvailableByteArrayBody;
import io.micronaut.http.body.stream.BaseSharedBuffer;
import io.micronaut.http.body.stream.BaseStreamingByteBody;
import io.micronaut.http.netty.body.StreamingNettyByteBody;
import io.netty.channel.EventLoop;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Exact read-only development profile; proves root ownership, never subscriber settlement. */
final class PinnedIngressSettlement {
    static final String HTTP_SHA256 = "e4ec29828c0c6201e7c2d878fd20991e01017dfa3fce47418c4c589b53c48637";
    static final String NETTY_SHA256 = "f03cad89ec16484a6b9021c8d5a376e39b8048857a5242df46aa435a91028920";
    private final Field sharedBuffer;
    private final Field reserved;
    private final Field buffer;
    private final Field fullSubscribers;
    private final Field working;

    PinnedIngressSettlement() {
        try {
            if (!artifactMatches(BaseStreamingByteBody.class, HTTP_SHA256)
                || !artifactMatches(BaseSharedBuffer.class, HTTP_SHA256)
                || !artifactMatches(AvailableByteArrayBody.class, HTTP_SHA256)
                || !artifactMatches(StreamingNettyByteBody.class, NETTY_SHA256)
                || !artifactMatches(StreamingNettyByteBody.SharedBuffer.class, NETTY_SHA256)) throw new IllegalStateException();
            sharedBuffer = BaseStreamingByteBody.class.getDeclaredField("sharedBuffer");
            reserved = BaseSharedBuffer.class.getDeclaredField("reserved");
            buffer = BaseSharedBuffer.class.getDeclaredField("buffer");
            fullSubscribers = BaseSharedBuffer.class.getDeclaredField("fullSubscribers");
            working = BaseSharedBuffer.class.getDeclaredField("working");
            validateField(sharedBuffer, BaseStreamingByteBody.class, BaseSharedBuffer.class,
                Modifier.PROTECTED | Modifier.FINAL);
            validateField(reserved, BaseSharedBuffer.class, int.class, Modifier.PRIVATE);
            validateField(buffer, BaseSharedBuffer.class, List.class, Modifier.PRIVATE);
            validateField(fullSubscribers, BaseSharedBuffer.class, List.class, Modifier.PRIVATE);
            validateField(working, BaseSharedBuffer.class, boolean.class, Modifier.PRIVATE);
        } catch (Exception failure) {
            throw new IllegalStateException("BFF_INGRESS_POLICY_UNAVAILABLE");
        }
    }

    static void validateField(Field field, Class<?> owner, Class<?> type, int modifiers) throws IllegalAccessException {
        if (field.getDeclaringClass() != owner || field.getType() != type || field.getModifiers() != modifiers
            || !field.trySetAccessible()) throw new IllegalAccessException();
    }

    private static boolean artifactMatches(Class<?> type, String expected) throws Exception {
        if (type.getClassLoader() != PinnedIngressSettlement.class.getClassLoader() || type.getModule().isNamed()) return false;
        var source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null || !source.getLocation().getProtocol().equals("file")) return false;
        Path path = Path.of(source.getLocation().toURI()).toRealPath();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.getFileName().toString().endsWith(".jar")) return false;
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[8192];
            int length;
            while ((length = input.read(bytes)) != -1) digest.update(bytes, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest()).equals(expected);
    }

    BooleanSupplier capture(ByteBody body, EventLoop loop) {
        if (body.getClass() == AvailableByteArrayBody.class) return loop::inEventLoop;
        if (body.getClass() != StreamingNettyByteBody.class) throw new IllegalStateException("BFF_INGRESS_POLICY_UNAVAILABLE");
        try {
            Object root = sharedBuffer.get(body);
            if (root == null || root.getClass() != StreamingNettyByteBody.SharedBuffer.class
                || ((StreamingNettyByteBody.SharedBuffer) root).eventLoop() != loop) throw new IllegalStateException();
            return () -> settled(root, loop);
        } catch (IllegalAccessException | RuntimeException failure) {
            throw new IllegalStateException("BFF_INGRESS_POLICY_UNAVAILABLE");
        }
    }

    private boolean settled(Object root, EventLoop loop) {
        if (!loop.inEventLoop()) return false;
        try {
            return reserved.getInt(root) == 0 && buffer.get(root) == null
                && fullSubscribers.get(root) == null && !working.getBoolean(root);
        } catch (IllegalAccessException | RuntimeException failure) {
            return false;
        }
    }
}
