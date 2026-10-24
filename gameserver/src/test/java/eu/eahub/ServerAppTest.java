package eu.eahub;

import eu.eahub.services.server.SocketManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAppTest {

    @Test
    void acceptedSocketHasReadTimeout() throws Exception {
        SocketManager socketManager = new SocketManager(null);
        ServerApp serverApp = new ServerApp(null, null, null, socketManager, null, null, null, null, null, null);
        AtomicReference<Socket> accepted = new AtomicReference<>();
        AtomicReference<Integer> timeout = new AtomicReference<>();
        CountDownLatch handled = new CountDownLatch(1);
        Function<Socket, Runnable> factory = socket -> () -> {
            accepted.set(socket);
            try {
                timeout.set(socket.getSoTimeout());
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                handled.countDown();
            }
        };

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Method method = ServerApp.class.getDeclaredMethod("startServerThread", ServerSocket.class, Function.class, boolean.class);
            method.setAccessible(true);
            method.invoke(serverApp, serverSocket, factory, true);

            try (Socket client = new Socket("127.0.0.1", serverSocket.getLocalPort())) {
                assertTrue(handled.await(5, TimeUnit.SECONDS));
                assertEquals(120_000, timeout.get());
            } finally {
                if (accepted.get() != null) {
                    accepted.get().close();
                }
            }
        } finally {
            Field field = ServerApp.class.getDeclaredField("clientHandlingExecutor");
            field.setAccessible(true);
            ((ExecutorService) field.get(serverApp)).shutdownNow();
        }
    }
}
