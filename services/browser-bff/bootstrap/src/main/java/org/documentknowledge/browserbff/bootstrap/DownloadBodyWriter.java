package org.documentknowledge.browserbff.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.ResponseBodyWriter;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.codec.CodecException;
import io.micronaut.http.server.netty.NettyHttpRequest;
import jakarta.inject.Singleton;
import java.io.OutputStream;
import org.documentknowledge.browserbff.adapters.inbound.DownloadDelivery;

/** Exact pinned writer profile; BodySizeLimits is an explicit value-only @Internal exception. */
@Context
@Singleton
@Produces({"application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"})
public final class DownloadBodyWriter implements ResponseBodyWriter<DownloadDelivery.Body> {
    private final DownloadBridge bridge;
    private final Class<?> factoryType;

    public DownloadBodyWriter(DownloadBridge bridge) {
        this.bridge = bridge;
        try {
            factoryType = Class.forName("io.micronaut.http.netty.body.NettyByteBodyFactory", false,
                ByteBodyFactory.class.getClassLoader());
            if (!PinnedIngressSettlement.artifactMatches(ByteBodyFactory.class, PinnedIngressSettlement.HTTP_SHA256)
                || !PinnedIngressSettlement.artifactMatches(BodySizeLimits.class, PinnedIngressSettlement.HTTP_SHA256)
                || !PinnedIngressSettlement.artifactMatches(factoryType, PinnedIngressSettlement.NETTY_SHA256))
                throw new IllegalStateException();
        } catch (Exception unsupported) { throw new IllegalStateException("BFF_DOWNLOAD_PROFILE_UNAVAILABLE"); }
    }

    @Override public CloseableByteBody writePiece(ByteBodyFactory factory, HttpRequest<?> request,
            HttpResponse<?> response, Argument<DownloadDelivery.Body> type, MediaType mediaType,
            DownloadDelivery.Body body) {
        if (!(body.owner() instanceof DownloadBridge.Producer producer) || !producer.ownedBy(bridge)) throw unavailable();
        try {
            if (factory.getClass() != factoryType || request.getClass() != NettyHttpRequest.class
                || !producer.belongsTo(bridge, body, ((NettyHttpRequest<?>) request).getChannelHandlerContext().channel()))
                throw unavailable();
            producer.claim();
            // Pinned public getter creates the adapter for this exact request's channel loop.
            // The caller's factory class does not attest that loop identity.
            ByteBodyFactory bound = ((NettyHttpRequest<?>) request).byteBodyFactory();
            if (bound.getClass() != factoryType) throw unavailable();
            return bound.adapt(producer, new BodySizeLimits(100_000_000, 65536),
                response.getHeaders(), producer::cancel);
        } catch (RuntimeException failure) { producer.cancel(); throw unavailable(); }
    }

    @Override public void writeTo(Argument<DownloadDelivery.Body> type, MediaType mediaType,
            DownloadDelivery.Body body, MutableHeaders headers, OutputStream output) {
        // No unbounded generic OutputStream fallback is part of the selected profile.
        if (body.owner() instanceof DownloadBridge.Producer producer && producer.ownedBy(bridge)) producer.cancel();
        throw unavailable();
    }

    private static CodecException unavailable() { return new CodecException("BFF_DOWNLOAD_PROFILE_UNAVAILABLE"); }
}
