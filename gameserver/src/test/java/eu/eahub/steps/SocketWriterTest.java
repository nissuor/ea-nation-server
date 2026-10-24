package eu.eahub.steps;

import eu.eahub.dto.SocketData;
import eu.eahub.services.server.SocketManager;
import eu.eahub.utils.Props;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketWriterTest {

    private static class TestProps extends Props {
        @Override
        public List<String> getTcpDebugExclusions() {
            return List.of();
        }

        @Override
        public boolean isTcpDebugEnabled() {
            return false;
        }
    }

    private static class TestSocket extends Socket {
        private final OutputStream outputStream;

        TestSocket(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("192.0.2.10", 12345);
        }
    }

    private SocketWriter socketWriter() {
        return new SocketWriter(new TestProps(), new SocketManager(null));
    }

    @Test
    void writeSendsHeaderAndContent() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Socket socket = new TestSocket(outputStream);

        socketWriter().write(socket, new SocketData("auth", null, Map.of("NAME", "player")));

        byte[] expected = new byte[]{'a', 'u', 't', 'h', 0, 0, 0, 0, 0, 0, 0, 24,
                'N', 'A', 'M', 'E', '=', 'p', 'l', 'a', 'y', 'e', 'r', 0};
        assertArrayEquals(expected, outputStream.toByteArray());
    }

    @Test
    void writeToAnotherSocketDoesNotWaitForABlockedWrite() throws Exception {
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
        Socket blockedSocket = new TestSocket(blockingStream);
        ByteArrayOutputStream otherOutputStream = new ByteArrayOutputStream();
        Socket otherSocket = new TestSocket(otherOutputStream);
        SocketWriter socketWriter = socketWriter();
        SocketData socketData = new SocketData("~png", null, null);

        Thread blockedThread = new Thread(() -> socketWriter.write(blockedSocket, socketData));
        blockedThread.start();
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));

        AtomicBoolean otherDone = new AtomicBoolean(false);
        Thread otherThread = new Thread(() -> {
            socketWriter.write(otherSocket, socketData);
            otherDone.set(true);
        });
        otherThread.start();
        otherThread.join(5000);
        boolean otherFinished = otherDone.get();

        releaseWrite.countDown();
        blockedThread.join(5000);
        otherThread.join(5000);

        assertTrue(otherFinished);
        assertFalse(blockedThread.isAlive());
    }

    @Test
    void writesToTheSameSocketStayInOrder() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Socket socket = new TestSocket(outputStream);
        SocketWriter socketWriter = socketWriter();

        socketWriter.write(socket, new SocketData("~png", null, null));
        socketWriter.write(socket, new SocketData("~png", null, null));

        assertTrue(outputStream.size() == 24);
    }
}
