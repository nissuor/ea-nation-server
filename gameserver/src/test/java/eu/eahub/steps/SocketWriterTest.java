package eu.eahub.steps;

import eu.eahub.dto.SocketData;
import eu.eahub.services.server.SocketManager;
import eu.eahub.utils.Props;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocketWriterTest {

    private SocketWriter socketWriter;

    @BeforeEach
    void setUp() {
        Props props = mock(Props.class);
        when(props.getTcpDebugExclusions()).thenReturn(List.of());
        socketWriter = new SocketWriter(props, new SocketManager(null));
    }

    private static Socket socket(OutputStream outputStream) throws IOException {
        Socket socket = mock(Socket.class);
        when(socket.getOutputStream()).thenReturn(outputStream);
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress("192.0.2.10", 12345));
        return socket;
    }

    @Test
    void writeHeaderAndContent() throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        socketWriter.write(socket(outputStream), new SocketData("auth", null, Map.of("NAME", "player")));
        socketWriter.write(socket(outputStream), new SocketData("~png", null, null));

        byte[] expected = new byte[]{'a', 'u', 't', 'h', 0, 0, 0, 0, 0, 0, 0, 24, 'N', 'A', 'M', 'E', '=', 'p', 'l', 'a', 'y', 'e', 'r', 0,
                '~', 'p', 'n', 'g', 0, 0, 0, 0, 0, 0, 0, 12};
        assertArrayEquals(expected, outputStream.toByteArray());
    }

    @Test
    void writeDoesNotWaitForAnotherSocket() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch releaseWrite = new CountDownLatch(1);
        OutputStream blockingStream = new OutputStream() {
            @Override
            public void write(byte[] bytes) throws IOException {
                writeStarted.countDown();
                try {
                    if (!releaseWrite.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Write was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }

            @Override
            public void write(int value) {
            }
        };
        Socket blockedSocket = socket(blockingStream);
        ByteArrayOutputStream otherOutputStream = new ByteArrayOutputStream();
        Socket otherSocket = socket(otherOutputStream);
        SocketData socketData = new SocketData("~png", null, null);

        Thread blockedThread = new Thread(() -> socketWriter.write(blockedSocket, socketData));
        blockedThread.start();
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));

        Thread otherThread = new Thread(() -> socketWriter.write(otherSocket, socketData));
        otherThread.start();
        otherThread.join(5000);
        boolean otherFinished = !otherThread.isAlive();

        releaseWrite.countDown();
        blockedThread.join(5000);

        assertTrue(otherFinished);
        assertEquals(12, otherOutputStream.size());
    }
}
