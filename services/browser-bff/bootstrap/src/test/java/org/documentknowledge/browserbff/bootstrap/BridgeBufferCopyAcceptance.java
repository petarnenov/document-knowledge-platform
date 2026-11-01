package org.documentknowledge.browserbff.bootstrap;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Fresh JVMs prove both actual Micronaut copy profiles before its static flag is initialized. */
public final class BridgeBufferCopyAcceptance {
    static void bothProfiles() throws Exception {
        for (boolean strict : new boolean[] {false, true}) {
            var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m", "-XX:ActiveProcessorCount=2",
                "-Dio.micronaut.buffer.netty.NettyReadBuffer.STRICT_REFCNT=" + strict,
                "-cp", System.getProperty("java.class.path"),
                BridgeBufferCopyAcceptance.class.getName(), Boolean.toString(strict))
                .inheritIO().start();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Bridge copy JVM timed out");
                if (process.exitValue() != 0) throw new AssertionError("Bridge copy profile failed: " + strict);
            } finally {
                if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            }
        }
    }

    public static void main(String[] args) {
        if (!System.getProperty("os.name").equals("Linux") || args.length != 1
            || !args[0].equals(System.getProperty("io.micronaut.buffer.netty.NettyReadBuffer.STRICT_REFCNT")))
            throw new IllegalArgumentException("Explicit fresh Linux copy profile required");
        BridgeAllocationPoolTest.assertActualMicronautConsumption();
        System.out.println("Bridge actual arena/copy profile PASS strict=" + args[0]);
    }
}
