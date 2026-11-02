package org.documentknowledge.devlogging.bootstrap;

import com.sun.security.auth.module.UnixSystem;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.documentknowledge.devlogging.adapters.outbound.persistence.JdbcLogManifests;
import org.documentknowledge.devlogging.application.LogManifestRegistry;
import org.documentknowledge.devlogging.application.RequestDeadline;
import org.documentknowledge.devlogging.domain.LogFailure;

/** Private operator composition; never starts the server or accepts credentials in arguments. */
final class PrivateManifestCommand {
    private PrivateManifestCommand() { }

    static int run(String[] args, PrintStream output) {
        try {
            var deadline = RequestDeadline.start();
            if (args == null || args.length != 9 || !"install-manifest".equals(args[0])
                    || !"--manifest".equals(args[1]) || !"--sha256".equals(args[3])
                    || !"--jdbc-url".equals(args[5]) || !"--password-file".equals(args[7])) invalid();
            String url = args[6];
            if (url == null || !url.matches("jdbc:postgresql://[A-Za-z0-9.-]+:[0-9]{1,5}/dkp_dev_logs")) invalid();
            int port = Integer.parseInt(url.substring(url.lastIndexOf(':') + 1, url.lastIndexOf('/')));
            if (port < 1 || port > 65535) invalid();
            var manifest = PrivateLogManifest.read(Path.of(args[2]), args[4]);
            var passwordFile = Path.of(args[8]);
            if (((Number) Files.getAttribute(passwordFile, "unix:uid", LinkOption.NOFOLLOW_LINKS)).longValue()
                    != new UnixSystem().getUid()) invalid();
            String password = new String(PrivateLogConfiguration.read(args[8], 512), StandardCharsets.US_ASCII);
            if (!password.matches("[A-Za-z0-9_-]{32,512}")) invalid();
            deadline.requireTime();
            var settings = new HikariConfig();
            settings.setJdbcUrl(url); settings.setUsername("dkp_log_admin"); settings.setPassword(password);
            settings.setMaximumPoolSize(1); settings.setMinimumIdle(0);
            settings.setConnectionTimeout(1000); settings.setValidationTimeout(500);
            settings.setInitializationFailTimeout(-1); settings.setPoolName("dkp-log-manifest-operator");
            settings.addDataSourceProperty("connectTimeout", "1"); settings.addDataSourceProperty("socketTimeout", "5");
            LogManifestRegistry.Installed installed;
            try (var pool = new HikariDataSource(settings)) {
                LogManifestRegistry registry = new JdbcLogManifests(pool);
                installed = registry.install(manifest, deadline);
            }
            output.println("{\"result\":\"INSTALLED\",\"manifestSha256\":\"" + installed.manifestSha256()
                + "\",\"buildId\":\"" + installed.build().buildId() + "\"}");
            return 0;
        } catch (LogFailure failure) {
            output.println("LOG_MANIFEST_INSTALL_" + failure.kind().name());
            return 1;
        } catch (Exception failure) {
            output.println("LOG_MANIFEST_INSTALL_UNAVAILABLE");
            return 1;
        }
    }

    private static void invalid() { throw new LogFailure(LogFailure.Kind.INVALID); }
}
