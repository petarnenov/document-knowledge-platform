package org.documentknowledge.browserbff.adapters.outbound;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.documentknowledge.browserbff.application.Readiness;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdkStartupProfileTest {
    private static final Map<String, String> PROFILE = Map.of(
        "java.util.concurrent.ForkJoinPool.common.parallelism", "2",
        "java.util.concurrent.ForkJoinPool.common.maximumSpares", "0",
        "jdk.httpclient.connectionPoolSize", "32", "jdk.httpclient.bufsize", "16384",
        "jdk.httpclient.redirects.retrylimit", "5", "jdk.httpclient.disableRetryConnect", "false",
        "jdk.httpclient.enableAllMethodRetry", "false");

    @Test void freshJvmRejectsMissingChangedAndCustomStartupProfile() throws Exception {
        check(PROFILE, null, 0);
        for (String key : PROFILE.keySet()) {
            var missing = new HashMap<>(PROFILE); missing.remove(key); check(missing, null, 23);
            var changed = new HashMap<>(PROFILE); changed.put(key, "9"); check(changed, null, 23);
        }
        for (String custom : List.of("java.util.concurrent.ForkJoinPool.common.threadFactory", "java.util.concurrent.ForkJoinPool.common.exceptionHandler")) {
            var changed = new HashMap<>(PROFILE); changed.put(custom, "synthetic.unsupported"); check(changed, null, 23);
        }
        check(PROFILE, "mismatch", 23);
        check(PROFILE, "factory", 23); check(PROFILE, "handler", 23);
    }
    private static void check(Map<String, String> profile, String argument, int expected) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        profile.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.add("-cp");
        command.add(List.of(JdkStartupProfileTest.class, OwnedJdkTransport.class, Readiness.class).stream()
            .map(type -> { try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); } catch (Exception error) { throw new AssertionError(error); } })
            .distinct().collect(Collectors.joining(File.pathSeparator)));
        command.add(Probe.class.getName()); if (argument != null) command.add(argument);
        var child = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            assertEquals(expected, child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally { child.destroyForcibly(); }
    }
    public static final class CustomFactory implements java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory {
        public java.util.concurrent.ForkJoinWorkerThread newThread(java.util.concurrent.ForkJoinPool pool) {
            return java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
        }
    }
    public static final class CustomHandler implements Thread.UncaughtExceptionHandler {
        public void uncaughtException(Thread thread, Throwable failure) { }
    }
    public static final class Probe {
        public static void main(String[] arguments) {
            if (arguments.length != 0 && arguments[0].equals("factory")) {
                System.setProperty("java.util.concurrent.ForkJoinPool.common.threadFactory", CustomFactory.class.getName());
                java.util.concurrent.ForkJoinPool.commonPool();
                System.clearProperty("java.util.concurrent.ForkJoinPool.common.threadFactory");
            } else if (arguments.length != 0 && arguments[0].equals("handler")) {
                System.setProperty("java.util.concurrent.ForkJoinPool.common.exceptionHandler", CustomHandler.class.getName());
                java.util.concurrent.ForkJoinPool.commonPool();
                System.clearProperty("java.util.concurrent.ForkJoinPool.common.exceptionHandler");
            } else if (arguments.length != 0) {
                // Controlled fresh-JVM mismatch: public pool initialized before the property was restored.
                System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "1");
                java.util.concurrent.ForkJoinPool.commonPool();
                System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "2");
            }
            try { OwnedJdkTransport.requireProfile(); }
            catch (IllegalStateException rejected) { System.exit(23); }
        }
    }
}
