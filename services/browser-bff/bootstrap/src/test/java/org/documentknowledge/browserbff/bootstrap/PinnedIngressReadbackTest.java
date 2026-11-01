package org.documentknowledge.browserbff.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.server.netty.handler.PipeliningServerHandler;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PinnedIngressReadbackTest {
    @TempDir Path temp;
    static class Fields {
        private int selected;
        public int publicField;
        private static int staticField;
        private final int finalField = 1;
    }
    static class Other {}

    @Test void exactLoadedJarAndSelectedFieldsReadActualValuesAfresh() throws Exception {
        assertTrue(PinnedIngressReadback.artifactMatches(PipeliningServerHandler.class));
        var readback = new PinnedIngressReadback();
        var handler = new PipeliningServerHandler(null, false);
        assertFalse(readback.matches(null));
        assertFalse(readback.matches(handler));
        handler.setBodySizeLimits(new BodySizeLimits(100000000, 131072));
        handler.setRequestDecompressionEnabled(false);
        assertTrue(readback.matches(handler));
        handler.setBodySizeLimits(new BodySizeLimits(100000001, 131072));
        assertFalse(readback.matches(handler));
        handler.setBodySizeLimits(new BodySizeLimits(100000000, 131073));
        assertFalse(readback.matches(handler));
        handler.setBodySizeLimits(new BodySizeLimits(100000000, 131072));
        handler.setRequestDecompressionEnabled(true);
        assertFalse(readback.matches(handler));
        handler.setRequestDecompressionEnabled(false);
        assertTrue(readback.matches(handler));
    }

    @Test void exactDeclaredTypeOwnerAndModifiersAndActualJvmAccessAreMandatory() throws Exception {
        var field = Fields.class.getDeclaredField("selected");
        PinnedIngressReadback.validateField(field, Fields.class, int.class);
        assertEquals(Modifier.PRIVATE, field.getModifiers());
        assertThrows(IllegalAccessException.class, () -> PinnedIngressReadback.validateField(field, Other.class, int.class));
        assertThrows(IllegalAccessException.class, () -> PinnedIngressReadback.validateField(field, Fields.class, boolean.class));
        for (String name : new String[]{"publicField", "staticField", "finalField"}) {
            assertThrows(IllegalAccessException.class, () -> PinnedIngressReadback.validateField(Fields.class.getDeclaredField(name), Fields.class, int.class));
        }
        // Actual strong-module denial, without JVM opens or SecurityManager simulation.
        var denied = String.class.getDeclaredField("hash");
        assertEquals(Modifier.PRIVATE, denied.getModifiers());
        assertFalse(denied.trySetAccessible());
        assertThrows(IllegalAccessException.class, () -> PinnedIngressReadback.validateField(denied, String.class, int.class));
        assertThrows(NoSuchFieldException.class, () -> PipeliningServerHandler.class.getDeclaredField("missing"));
    }

    @Test void unselectedDirectoryNullSourceAndDifferentJarDigestAreNotAccepted() throws Exception {
        assertFalse(PinnedIngressReadback.artifactMatches(String.class));
        assertFalse(PinnedIngressReadback.artifactMatches(PinnedIngressReadbackTest.class));
        Path jar = temp.resolve("other.jar");
        String name = Fields.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
             var input = Fields.class.getResourceAsStream("/" + name)) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry(name));
            input.transferTo(output);
            output.closeEntry();
        }
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null)) {
            var otherArtifact = loader.loadClass(Fields.class.getName());
            assertFalse(PinnedIngressReadback.artifactMatches(otherArtifact));
        }
    }
    @Test void unknownApplicationLoaderRejectsConstructionWithFiniteUnavailableFailure() throws Exception {
        String selected = PinnedIngressReadback.class.getName();
        byte[] bytes;
        try (var input = PinnedIngressReadback.class.getResourceAsStream("PinnedIngressReadback.class")) {
            assertNotNull(input); bytes = input.readAllBytes();
        }
        var isolated = new ClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(selected)) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    var type = findLoadedClass(name);
                    if (type == null) type = defineClass(name, bytes, 0, bytes.length);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        var constructor = isolated.loadClass(selected).getDeclaredConstructor();
        assertTrue(constructor.trySetAccessible());
        var rejected = assertThrows(java.lang.reflect.InvocationTargetException.class, constructor::newInstance);
        assertInstanceOf(IllegalStateException.class, rejected.getCause());
        assertEquals("BFF_INGRESS_POLICY_UNAVAILABLE", rejected.getCause().getMessage());
        assertNull(rejected.getCause().getCause());
    }

}
