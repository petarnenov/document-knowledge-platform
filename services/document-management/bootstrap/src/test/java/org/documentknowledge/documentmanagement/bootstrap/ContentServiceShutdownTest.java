package org.documentknowledge.documentmanagement.bootstrap;

import io.micronaut.context.ApplicationContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.documentknowledge.documentmanagement.application.*;
import org.documentknowledge.documentmanagement.application.upload.*;
import org.documentknowledge.documentmanagement.domain.*;
import org.documentknowledge.documentmanagement.domain.upload.UploadFormat;
import org.documentknowledge.identity.Actor;
import org.documentknowledge.identity.AuthorityDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.documentknowledge.documentmanagement.domain.upload.UploadModels.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real container lifecycle and disk/channel observations; all content facts are synthetic. */
class ContentServiceShutdownTest {
    @TempDir Path temporary;

    @Test void contextShutdownClosesFactoryProducedContentStreamAndReleasesExclusiveSpool() throws Exception {
        Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rwx------"));
        byte[] bytes = "synthetic context shutdown content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Instant now = Instant.now();
        UUID document = UUID.randomUUID(), version = UUID.randomUUID(), receiptId = UUID.randomUUID();
        UUID upload = UUID.randomUUID(), evidenceId = UUID.randomUUID();
        var actor = new Actor("https://identity.example/realms/dkp", "synthetic-reviewer");
        var caller = new DocumentCaller(actor, "tenant-a", () -> AuthorityDecision.ALLOW);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var original = new ObjectIdentity(new ObjectKey("dkp-originals", "a".repeat(64)+"/"+document+"/"+upload), "immutable-version");
        var receipt = new UploadReceipt(receiptId, upload, caller.tenant(), document, caller.principal(), hash, bytes.length,
            UploadFormat.PDF, original, evidenceId, now, now.plusSeconds(3600));
        var evidence = new VerificationEvidence(evidenceId, caller.tenant(), upload, original,
            new ScanEvidence(hash, bytes.length, "1.5.4", "synthetic-standard", now, "d".repeat(64), now),
            new InspectionEvidence(UploadFormat.PDF, UploadFormat.PDF.mime(), bytes.length, hash, 1, "1", "f".repeat(64), "sha256:"+"b".repeat(64), now), now);
        var consumption = new ReceiptConsumption(caller.tenant(), receiptId, document, version, caller.principal(), "append", "c".repeat(64), now);
        var aggregate = new DocumentAggregate(document, caller.tenant(), caller.principal(), now, 1, new Metadata("Synthetic", ""),
            Set.of(new AclRule(caller.principal(), Action.CONTENT_REVIEW, AclRule.Effect.ALLOW)), List.of(),
            List.of(new DocumentVersion(version, document, 1, hash, UploadFormat.PDF.mime(), receiptId.toString(), "verified-upload", caller.principal(), now)));
        UploadRepository uploads = proxy(UploadRepository.class, (method, args) -> {
            assertEquals(caller.tenant(), args[0]);
            return switch (method) {
                case "receipt" -> { assertEquals(receiptId, args[1]); yield Optional.of(receipt); }
                case "consumption" -> { assertEquals(receiptId, args[1]); yield Optional.of(consumption); }
                case "evidence" -> { assertEquals(evidenceId, args[1]); yield Optional.of(evidence); }
                default -> throw new AssertionError("Unexpected repository operation: " + method);
            };
        });
        DocumentRepository repository = proxy(DocumentRepository.class, (method, args) -> switch (method) {
            case "find" -> { assertEquals(caller.tenant(), args[0]); assertEquals(document, args[1]); yield Optional.of(aggregate); }
            case "uploads" -> uploads;
            default -> throw new AssertionError("Unexpected document operation: " + method);
        });
        DocumentUnitOfWork transactions = new DocumentUnitOfWork() {
            public <T> T execute(Function<DocumentRepository, T> work) { return work.apply(repository); }
        };
        var context = ApplicationContext.builder().properties(Map.of(
            "dkp.dm.upload.enabled", true, "dkp.dm.upload.private-spool-path", temporary.toString(),
            "dkp.dm.storage.endpoint", "https://storage.example:9000/", "dkp.dm.storage.region", "us-east-1")).build();
        var storage = proxy(UploadStoragePort.class, (method, args) -> {
            assertEquals("open", method); assertEquals(original, args[0]); return new ByteArrayInputStream(bytes);
        });
        var connections = proxy(UploadRuntime.Connections.class, (method, args) -> switch (method) {
            case "storage" -> new UploadRuntime.Storage(storage, () -> true, () -> {});
            case "cleanup" -> new UploadRuntime.Cleanup(proxy(CleanupStoragePort.class, (m, a) -> { throw new AssertionError("No cleanup operation"); }), () -> true, () -> {});
            default -> throw new AssertionError("No scanner, inspector or network required");
        });
        var runtime = new UploadRuntime(context.getEnvironment(), Clock.systemUTC(),
            (path, maximum, privateRead) -> (path+"-synthetic-credential").getBytes(java.nio.charset.StandardCharsets.US_ASCII), connections);
        var bindings = new DocumentFactory.Bindings(transactions, (a,t,x,r) -> AuthorityDecision.ALLOW, null, null, runtime);
        context.registerSingleton(DocumentFactory.Bindings.class, bindings);
        ContentService service = null;
        try {
            context.start();
            service = context.getBean(ContentService.class); // Production @Factory producer, not a directly constructed service.
            assertSame(service, context.getBean(ContentService.class));
            var response = service.open(caller, document, version, null);
            var stream = response.openStream();
            assertEquals(Byte.toUnsignedInt(bytes[0]), stream.read()); // Leave a live channel and reservation at shutdown.
            Path data = temporary.resolve("content-gateway/slot-0.bytes");
            assertTrue(Files.exists(data)); assertEquals(bytes.length, Files.size(data));
            try (var channel = FileChannel.open(temporary.resolve(".content-gateway.lock"), StandardOpenOption.WRITE)) {
                assertThrows(java.nio.channels.OverlappingFileLockException.class, channel::tryLock);
            }
            context.close();
            assertThrows(IOException.class, stream::read, "Context must close the live content channel");
            assertEquals(503, assertThrows(DmFailure.class, response::openStream).status());
            try (var files = Files.list(temporary.resolve("content-gateway"))) {
                assertEquals(List.of(".owner"), files.map(p -> p.getFileName().toString()).sorted().toList());
            }
            try (var channel = FileChannel.open(temporary.resolve(".content-gateway.lock"), StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                assertNotNull(lock, "A new owner can acquire the released exclusive spool lock");
            }
        } finally {
            context.close();
            if (service != null) service.close(); // Also clean up safely if a lifecycle regression makes this test fail.
            runtime.close();
        }
    }

    @FunctionalInterface interface Invocation { Object call(String method, Object[] arguments) throws Throwable; }
    private static <T> T proxy(Class<T> type, Invocation invoke) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (instance, method, arguments) -> invoke.call(method.getName(), arguments)));
    }
}
