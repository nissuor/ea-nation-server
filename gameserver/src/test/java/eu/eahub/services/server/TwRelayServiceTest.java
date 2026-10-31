package eu.eahub.services.server;

import eu.eahub.config.TwConfig;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.GameEntity;
import eu.eahub.entities.core.PersonaEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static eu.eahub.services.server.GameServerService.PS2_TW04;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwRelayServiceTest {

    // CommUDP control packets as DSOCKF.IRX writes them: two little-endian words
    private static final byte[] POKE = {5, 0, 0, 0, 0, 0, 0, 0};
    private static final byte[] INIT = {1, 0, 0, 0, 0x2a, 0x17, 0, 0};
    private static final byte[] ACK = {2, 0, 0, 0, 0x2a, 0x17, 0, 0};
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    private final AtomicLong clock = new AtomicLong();
    private final List<AutoCloseable> opened = new ArrayList<>();
    private TwConfig config;
    private TwRelayService relay;
    private int port;

    @BeforeEach
    void setup() throws Exception {
        config = new TwConfig();
        config.getRelay().setEnabled(true);
        config.getRelay().setPublicAddress("203.0.113.9");
        relay = new TwRelayService(config);
        relay.setClock(clock::get);
        try (DatagramSocket free = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0))) {
            port = free.getLocalPort();
        }
        relay.start(LOOPBACK, port);
    }

    @AfterEach
    void teardown() throws Exception {
        relay.stop();
        for (AutoCloseable socket : opened) {
            socket.close();
        }
    }

    /**
     * A player's UDP socket; the address must be a loopback address
     */
    private DatagramSocket client(String address) throws Exception {
        DatagramSocket socket = new DatagramSocket(new InetSocketAddress(address, 0));
        socket.setSoTimeout(300);
        opened.add(socket);
        return socket;
    }

    private DatagramSocket client() throws Exception {
        return client(LOOPBACK.getHostAddress());
    }

    /**
     * Whether the whole 127/8 block is on the loopback interface (Linux), which tests of two players on different
     * addresses need
     */
    private static boolean hasSecondLoopback() {
        try (DatagramSocket ignored = new DatagramSocket(new InetSocketAddress("127.0.0.2", 0))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void send(DatagramSocket socket, byte[] data) throws Exception {
        socket.send(new DatagramPacket(data, data.length, new InetSocketAddress(LOOPBACK, port)));
    }

    private void send(DatagramSocket socket, String data) throws Exception {
        send(socket, data.getBytes(StandardCharsets.ISO_8859_1));
    }

    private byte[] receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[4096], 4096);
        socket.receive(packet);
        assertEquals(port, packet.getPort());
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    private void empty(DatagramSocket socket) {
        assertThrows(SocketTimeoutException.class, () -> socket.receive(new DatagramPacket(new byte[4096], 4096)));
    }

    @Test
    void learnsBothNatMappings() throws Exception {
        DatagramSocket host = client();
        DatagramSocket guest = client();
        relay.register("match", host.getLocalAddress(), guest.getLocalAddress());
        send(guest, INIT);
        empty(host); // Host not learned yet; the guest retries every second
        send(host, POKE); // From whatever port the host's NAT chose
        empty(guest); // POKE only opens the host's mapping
        send(guest, INIT);
        assertArrayEquals(INIT, receive(host));
        send(host, ACK);
        assertArrayEquals(ACK, receive(guest));
        byte[] binary = {0, -1, 4, 10, 0, 127};
        send(guest, binary);
        assertArrayEquals(binary, receive(host));
        send(host, binary);
        assertArrayEquals(binary, receive(guest));
        relay.release("match");
        send(host, "after release");
        empty(guest);
        relay.register("rematch", host.getLocalAddress(), guest.getLocalAddress());
    }

    @Test
    void playersSharingOneIp() throws Exception {
        DatagramSocket host = client();
        DatagramSocket guest = client();
        DatagramSocket host2 = client();
        DatagramSocket guest2 = client();
        InetAddress shared = host.getLocalAddress();
        relay.register("match", shared, shared);
        // A second host from the same IP must wait until the first host is learned
        assertThrows(IllegalStateException.class, () -> relay.register("early", shared, shared));
        send(host, POKE);
        send(guest, INIT);
        assertArrayEquals(INIT, receive(host));
        relay.register("second", shared, shared);
        send(host2, POKE);
        send(guest2, INIT);
        assertArrayEquals(INIT, receive(host2));
        empty(host);
        send(guest, "first match");
        assertArrayEquals("first match".getBytes(), receive(host));
        send(host2, "second match");
        assertArrayEquals("second match".getBytes(), receive(guest2));
        empty(guest);
    }

    @Test
    void ignoresStrayPackets() throws Exception {
        DatagramSocket host = client();
        DatagramSocket guest = client();
        DatagramSocket impostor = client();
        relay.register("match", host.getLocalAddress(), guest.getLocalAddress());
        send(host, "not a poke");
        send(guest, INIT);
        empty(host);
        send(host, POKE);
        send(guest, INIT);
        receive(host);
        send(impostor, INIT);
        send(impostor, POKE);
        send(impostor, "new source port"); // Both players share this IP: a new port is not adopted
        send(host, new byte[TwRelayService.MAX_PACKET + 1]);
        empty(guest);
        send(host, "valid");
        assertArrayEquals("valid".getBytes(), receive(guest));
        empty(host);
    }

    @Test
    void followsMovedPort() throws Exception {
        assumeTrue(hasSecondLoopback());
        DatagramSocket host = client("127.0.0.2");
        DatagramSocket guest = client("127.0.0.3");
        DatagramSocket moved = client("127.0.0.3");
        relay.register("match", host.getLocalAddress(), guest.getLocalAddress());
        send(host, POKE);
        send(guest, INIT);
        assertArrayEquals(INIT, receive(host));
        send(host, "shot");
        assertArrayEquals("shot".getBytes(), receive(guest));
        send(moved, "too early"); // The guest's current port was just heard from
        empty(host);
        clock.addAndGet(TwRelayService.REBIND_QUIET);
        send(moved, "from new port");
        assertArrayEquals("from new port".getBytes(), receive(host));
        send(host, "reply");
        assertArrayEquals("reply".getBytes(), receive(moved));
        empty(guest);
        send(guest, "old port"); // The abandoned mapping no longer belongs to the match
        empty(host);
    }

    @Test
    void expiresSessions() throws Exception {
        DatagramSocket host = client();
        DatagramSocket guest = client();
        relay.register("pending", host.getLocalAddress(), guest.getLocalAddress());
        clock.addAndGet(TwRelayService.START_TIMEOUT);
        relay.expire();
        relay.register("active", host.getLocalAddress(), guest.getLocalAddress());
        send(host, POKE);
        send(guest, INIT);
        receive(host);
        clock.addAndGet(TwRelayService.IDLE_TIMEOUT);
        relay.expire();
        send(host, "late");
        empty(guest);
        relay.register("new", host.getLocalAddress(), guest.getLocalAddress());
    }

    @Test
    void disabledAndConflictingBind() throws Exception {
        TwRelayService disabled = new TwRelayService(new TwConfig());
        disabled.start();
        assertFalse(disabled.isEnabled());
        assertThrows(IllegalStateException.class, () -> disabled.register("x", LOOPBACK, LOOPBACK));
        disabled.stop();
        TwRelayService second = new TwRelayService(config);
        assertThrows(SocketException.class, () -> second.start(LOOPBACK, port));
    }

    @Test
    void reservesSessions() throws Exception {
        GameEntity game = new GameEntity();
        game.setId(7L);
        game.setVers(PS2_TW04);
        SocketWrapper host = player(1L, "198.51.100.2");
        SocketWrapper guest = player(2L, "198.51.100.3");
        assertEquals(Map.of(1L, "203.0.113.9", 2L, "203.0.113.9"), relay.reserve(game, host, guest));
        // A second reservation for the same game is refused until the first is released
        assertThrows(IllegalStateException.class, () -> relay.reserve(game, host, guest));
        relay.release(game);
        assertEquals(2, relay.reserve(game, host, guest).size());
        config.getRelay().setEnabled(false);
        assertEquals(Map.of(), relay.reserve(game, host, guest));
    }

    private static SocketWrapper player(Long personaId, String address) throws Exception {
        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getByName(address));
        PersonaEntity persona = new PersonaEntity();
        persona.setId(personaId);
        SocketWrapper wrapper = new SocketWrapper();
        wrapper.setSocket(socket);
        wrapper.setPersonaEntity(persona);
        return wrapper;
    }
}
