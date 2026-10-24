package eu.eahub.config;

import eu.eahub.utils.Props;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunnelHandlerTest {

    private DefaultHttpRequest request() {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/test");
    }

    private DefaultHttpContent content(String text) {
        return new DefaultHttpContent(Unpooled.wrappedBuffer(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void contentUnderTheCapIsKeptOpen() {
        EmbeddedChannel channel = new EmbeddedChannel(new TunnelHandler(new Props()));

        channel.writeInbound(request());
        channel.writeInbound(content("NAME=player"));

        assertTrue(channel.isOpen());
        channel.close();
    }

    @Test
    void contentOverTheCapClosesTheChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new TunnelHandler(new Props()));
        String chunk = "x".repeat(TunnelHandler.MAX_REQUEST_SIZE + 1);

        channel.writeInbound(request());
        channel.writeInbound(content(chunk));

        assertFalse(channel.isOpen());
    }

    @Test
    void chunksOverTheCapTogetherCloseTheChannel() {
        EmbeddedChannel channel = new EmbeddedChannel(new TunnelHandler(new Props()));
        String chunk = "x".repeat(TunnelHandler.MAX_REQUEST_SIZE / 2 + 1);

        channel.writeInbound(request());
        channel.writeInbound(content(chunk));
        assertTrue(channel.isOpen());
        channel.writeInbound(content(chunk));

        assertFalse(channel.isOpen());
    }
}
