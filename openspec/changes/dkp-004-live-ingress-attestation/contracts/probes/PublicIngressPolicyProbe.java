import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.stream.BodySizeLimits;
import io.micronaut.http.server.netty.configuration.NettyHttpServerConfiguration;
import io.micronaut.http.server.netty.handler.OutboundAccess;
import io.micronaut.http.server.netty.handler.PipeliningServerHandler;
import io.micronaut.http.server.netty.handler.RequestHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpRequest;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Research-only public-API observation. No private fields, network, or application bindings. */
public final class PublicIngressPolicyProbe {
    private static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        require(java.lang.reflect.Modifier.isFinal(PipeliningServerHandler.class.getModifiers()));
        var config = new NettyHttpServerConfiguration();
        config.setRequestDecompressionEnabled(false);
        config.setMaxRequestBufferSize(131072);
        var handler = new PipeliningServerHandler(new RequestHandler() {
            public void accept(ChannelHandlerContext ctx, HttpRequest request, CloseableByteBody body, OutboundAccess access) {
                throw new AssertionError("Probe must never send a request or body");
            }
            public void handleUnboundError(Throwable cause) { throw new AssertionError(cause); }
        });
        handler.setRequestDecompressionEnabled(false);
        handler.setBodySizeLimits(new BodySizeLimits(100000000, 131072));
        var channel = new EmbeddedChannel(handler);
        try {
            Object identity = channel.pipeline().get(PipeliningServerHandler.class);
            boolean autoRead = channel.config().isAutoRead();
            handler.setRequestDecompressionEnabled(true);
            require(!config.isRequestDecompressionEnabled());
            require(identity == channel.pipeline().get(PipeliningServerHandler.class));
            require(autoRead == channel.config().isAutoRead());
            System.out.println("PROBE_PASS same_instance_decompression_setter_leaves_public_config_and_identity_unchanged");
            handler.setBodySizeLimits(BodySizeLimits.UNLIMITED);
            require(config.getMaxRequestBufferSize() == 131072);
            require(identity == channel.pipeline().get(PipeliningServerHandler.class));
            require(autoRead == channel.config().isAutoRead());
            System.out.println("PROBE_PASS same_instance_limit_setter_leaves_public_config_and_identity_unchanged");
            Method[] methods = PipeliningServerHandler.class.getMethods();
            require(Arrays.stream(methods).noneMatch(m -> m.getParameterCount() == 0 &&
                (m.getReturnType() == BodySizeLimits.class || m.getName().toLowerCase().contains("decompression"))));
            require(PipeliningServerHandler.class.getMethod("setBodySizeLimits", BodySizeLimits.class).getReturnType() == void.class);
            require(PipeliningServerHandler.class.getMethod("setRequestDecompressionEnabled", boolean.class).getReturnType() == void.class);
            System.out.println("PROBE_PASS public_policy_setters_have_no_readback_api");
        } finally { channel.finishAndReleaseAll(); }
    }
}
