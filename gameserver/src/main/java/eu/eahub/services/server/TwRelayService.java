package eu.eahub.services.server;

import eu.eahub.config.TwConfig;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.GameEntity;
import eu.eahub.utils.TwAddressUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Optional UDP relay for TW04 matches, so that neither player needs a port forward
 * <p>
 * Both players send first: the match host (DSOCKF.IRX's CommUDP listener) sends an 8-byte POKE {5,0} every second to the
 * address it was given while it waits, and the guest (the connector) sends an 8-byte INIT {1,ident} every second. Each
 * player's NAT mapping is therefore learned from its own first packet, whatever port the NAT chose, and the packet type
 * says which side of which reserved match it belongs to. The listener accepts the INIT from any source and the
 * connector follows a POKE's source, so both then talk only to this socket. If a home router later moves a player to a
 * new port, the relay follows it once the old port has gone quiet
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TwRelayService {

    // The game always sends peer traffic to UDP 3658, so remote players can only reach a relay on that port
    static final int GAME_PEER_PORT = 3658;
    static final int MAX_PACKET = 2048;
    static final int MAX_MATCHES = 64;
    static final long START_TIMEOUT = Duration.ofSeconds(60).toNanos();
    static final long IDLE_TIMEOUT = Duration.ofMinutes(2).toNanos();
    static final long MAX_AGE = Duration.ofHours(6).toNanos();
    static final long REBIND_QUIET = Duration.ofSeconds(1).toNanos(); // A quiet endpoint may be replaced by its player's new NAT port
    private static final long RATE_WINDOW = Duration.ofSeconds(1).toNanos();
    private static final int MAX_PACKETS_PER_WINDOW = 1000;
    private static final int INIT = 1;
    private static final int POKE = 5;

    private final TwConfig twConfig;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<InetSocketAddress, Peer> endpoints = new HashMap<>();
    @Setter(AccessLevel.PACKAGE)
    private LongSupplier clock = System::nanoTime; // Replaced by the tests
    private volatile DatagramSocket socket;
    private Thread worker;

    /**
     * A reserved match: the host and the guest
     */
    private static class Session {
        private String id;
        private long created;
        private Peer host;
        private Peer guest;
    }

    /**
     * One player of a reserved match, and the NAT mapping learned from their first packet
     */
    private static class Peer {
        private Session session;
        private InetAddress address;
        private boolean host;
        private InetSocketAddress endpoint;
        private long lastSeen;
        private long rateWindow;
        private long forwarded;
        private int packets;

        private String role() {
            return host ? "host" : "guest";
        }

        private Peer other() {
            return session.host == this ? session.guest : session.host;
        }
    }

    /**
     * Check if the relay is enabled in the configuration
     *
     * @return true if tw04.relay.enabled is set
     */
    public boolean isEnabled() {
        return twConfig.getRelay().isEnabled();
    }

    /**
     * Validate the relay configuration and start listening on UDP 3658, if the relay is enabled
     *
     * @throws IOException if the UDP socket cannot be bound
     */
    @PostConstruct
    public void start() throws IOException {
        if (!isEnabled()) {
            return;
        }
        if (TwAddressUtils.parseIpv4(twConfig.getRelay().getPublicAddress()) == null) {
            throw new IllegalArgumentException("tw04.relay.public-address must be a numeric IPv4 address");
        }
        start(null, GAME_PEER_PORT);
    }

    /**
     * Start listening
     *
     * @param bind the address to bind, or null for every address
     * @param port the UDP port
     * @throws IOException if the UDP socket cannot be bound
     */
    void start(InetAddress bind, int port) throws IOException {
        DatagramSocket opened = new DatagramSocket(null);
        try {
            opened.setReuseAddress(false);
            opened.bind(new InetSocketAddress(bind, port));
            opened.setSoTimeout(1000);
        } catch (IOException e) {
            opened.close();
            throw e;
        }
        socket = opened;
        worker = new Thread(() -> receive(opened), "tw04-udp-relay");
        worker.setDaemon(true);
        worker.start();
        log.info("TW04 relay listening on UDP {}", port);
    }

    /**
     * Stop the relay and release every reserved match
     */
    @PreDestroy
    public void stop() {
        DatagramSocket opened = socket;
        if (opened != null) {
            opened.close();
        }
        if (worker != null) {
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            for (String id : List.copyOf(sessions.keySet())) {
                release(id);
            }
        }
    }

    /**
     * Route a TW04 session through the relay: both players are given the relay's public address in +ses
     *
     * @param game  the game
     * @param host  the host's connection
     * @param guest the guest's connection
     * @return the address each player connects to, by persona id, or an empty map when the relay is off
     * @throws IllegalStateException if the relay cannot take the match
     */
    public Map<Long, String> reserve(GameEntity game, SocketWrapper host, SocketWrapper guest) {
        if (!isEnabled()) {
            return Map.of();
        }
        register(String.valueOf(game.getId()), host.getSocket().getInetAddress(), guest.getSocket().getInetAddress());
        String address = twConfig.getRelay().getPublicAddress();
        return Map.of(host.getPersonaEntity().getId(), address, guest.getPersonaEntity().getId(), address);
    }

    /**
     * Release the route when the session is reported, cancelled or expires; a lobby disconnect does not end it, as
     * the consoles leave the lobby while they play
     *
     * @param game the game
     */
    public void release(GameEntity game) {
        release(String.valueOf(game.getId()));
    }

    /**
     * Reserve a route for an accepted match
     * Both players may share one public IP. Only one not-yet-learned host and one not-yet-learned guest per source IP
     * may wait at a time, so a first packet can never be attributed to the wrong match
     *
     * @param id           the match id
     * @param hostAddress  the host's (listener's) address
     * @param guestAddress the guest's (connector's) address
     * @throws IllegalStateException if the relay cannot take the match
     */
    synchronized void register(String id, InetAddress hostAddress, InetAddress guestAddress) {
        if (!isEnabled() || socket == null || socket.isClosed()) {
            throw new IllegalStateException("Relay is unavailable");
        }
        expire();
        if (!(hostAddress instanceof Inet4Address) || !(guestAddress instanceof Inet4Address) || sessions.containsKey(id)
                || sessions.size() >= MAX_MATCHES) {
            throw new IllegalStateException("Relay is full or the match is already reserved");
        }
        if (isWaiting(hostAddress, true) || isWaiting(guestAddress, false)) {
            throw new IllegalStateException("Another match from this address is still connecting; try again shortly");
        }
        Session session = new Session();
        session.id = id;
        session.created = clock.getAsLong();
        session.host = peer(session, hostAddress, true);
        session.guest = peer(session, guestAddress, false);
        sessions.put(id, session);
        log.info("TW04 relay reserved for {} and {}", hostAddress.getHostAddress(), guestAddress.getHostAddress());
    }

    /**
     * Whether a player with this address and role is still waiting to be learned
     *
     * @param address the player's address
     * @param host    the role
     * @return true if one is
     */
    private boolean isWaiting(InetAddress address, boolean host) {
        for (Session session : sessions.values()) {
            for (Peer peer : List.of(session.host, session.guest)) {
                if (peer.endpoint == null && peer.host == host && peer.address.equals(address)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Create a player of a reserved match
     *
     * @param session the match
     * @param address the player's address
     * @param host    the role
     * @return the player
     */
    private Peer peer(Session session, InetAddress address, boolean host) {
        Peer peer = new Peer();
        peer.session = session;
        peer.address = address;
        peer.host = host;
        peer.lastSeen = session.created;
        peer.rateWindow = session.created;
        return peer;
    }

    /**
     * Receive and forward packets until the socket is closed
     *
     * @param opened the relay socket
     */
    private void receive(DatagramSocket opened) {
        byte[] buffer = new byte[MAX_PACKET + 1];
        while (!opened.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                opened.receive(packet);
                forward(opened, packet);
            } catch (SocketTimeoutException ignored) {
                synchronized (this) {
                    expire();
                }
            } catch (IOException e) {
                if (!opened.isClosed()) {
                    log.warn("TW04 relay receive/send failed: {}", e.toString());
                }
            } catch (RuntimeException e) {
                log.error("TW04 relay failed to forward a packet", e);
            }
        }
    }

    /**
     * Get the CommUDP control type of a packet: two IOP-native (little-endian) words
     *
     * @param packet the received packet
     * @return POKE, INIT, or 0 for game data
     */
    static int control(DatagramPacket packet) {
        if (packet.getLength() != 8) {
            return 0;
        }
        byte[] data = packet.getData();
        int offset = packet.getOffset();
        int type = (data[offset] & 0xff) | (data[offset + 1] & 0xff) << 8 | (data[offset + 2] & 0xff) << 16 | data[offset + 3] << 24;
        boolean zero = data[offset + 4] == 0 && data[offset + 5] == 0 && data[offset + 6] == 0 && data[offset + 7] == 0;
        if (type == POKE && zero) {
            return POKE;
        }
        if (type == INIT && !zero) {
            return INIT;
        }
        return 0;
    }

    /**
     * Forward a packet to the sender's opponent
     *
     * @param opened the relay socket
     * @param packet the received packet
     * @throws IOException if the packet cannot be sent
     */
    private synchronized void forward(DatagramSocket opened, DatagramPacket packet) throws IOException {
        expire();
        if (packet.getLength() == 0 || packet.getLength() > MAX_PACKET) {
            return;
        }
        InetSocketAddress source = (InetSocketAddress) packet.getSocketAddress();
        int control = control(packet);
        long now = clock.getAsLong();
        Peer sender = endpoints.get(source);
        if (sender == null && control != 0) {
            sender = learn(source, control);
        }
        if (sender == null) {
            sender = rebind(source, now);
        }
        if (sender == null) {
            return;
        }
        if (now - sender.rateWindow >= RATE_WINDOW) {
            sender.rateWindow = now;
            sender.packets = 0;
        }
        if (++sender.packets > MAX_PACKETS_PER_WINDOW) {
            return;
        }
        sender.lastSeen = now;
        // A POKE only opens the host's NAT mapping; the guest's INIT now has somewhere to go
        if (sender.host && control == POKE) {
            return;
        }
        Peer target = sender.other();
        if (target.endpoint == null) {
            return; // The guest retries its INIT every second
        }
        opened.send(new DatagramPacket(packet.getData(), packet.getOffset(), packet.getLength(), target.endpoint));
        if (++sender.forwarded == 1) {
            log.info("TW04 relay forwarding {} -> {}", sender.endpoint, target.endpoint);
        }
    }

    /**
     * Learn a NAT mapping only from the packet its role sends first: a host's POKE or a guest's INIT, from the IP the
     * authenticated lobby connection came from
     *
     * @param source  the packet's source
     * @param control the packet's control type
     * @return the player, or null if no match of that IP waits for this role
     */
    private Peer learn(InetSocketAddress source, int control) {
        boolean host = control == POKE;
        Peer found = null;
        for (Session session : sessions.values()) {
            for (Peer peer : List.of(session.host, session.guest)) {
                if (peer.endpoint == null && peer.host == host && peer.address.equals(source.getAddress())) {
                    found = peer;
                }
            }
        }
        if (found == null) {
            return null;
        }
        found.endpoint = source;
        endpoints.put(source, found);
        log.info("TW04 relay learned {} endpoint {}", found.role(), source);
        return found;
    }

    /**
     * Follow a player whose router moved them to a new port mid-match. Only when exactly one learned endpoint has that
     * IP (two players behind one IP cannot be told apart by address) and it has been quiet, so a stray packet cannot
     * take over a working route
     *
     * @param source the packet's source
     * @param now    the current time
     * @return the player, or null if the packet is not adopted
     */
    private Peer rebind(InetSocketAddress source, long now) {
        Peer found = null;
        int matches = 0;
        for (Session session : sessions.values()) {
            for (Peer peer : List.of(session.host, session.guest)) {
                if (peer.endpoint != null && peer.address.equals(source.getAddress())) {
                    found = peer;
                    matches++;
                }
            }
        }
        if (found == null || matches > 1 || now - found.lastSeen < REBIND_QUIET) {
            return null;
        }
        log.warn("TW04 relay {} endpoint moved {} -> {}", found.role(), found.endpoint, source);
        endpoints.remove(found.endpoint);
        found.endpoint = source;
        endpoints.put(source, found);
        return found;
    }

    /**
     * Release the matches that never connected, went idle, or lasted too long
     */
    synchronized void expire() {
        long now = clock.getAsLong();
        for (Session session : List.copyOf(sessions.values())) {
            boolean pending = session.host.endpoint == null || session.guest.endpoint == null;
            if ((pending && now - session.created >= START_TIMEOUT)
                    || now - Math.min(session.host.lastSeen, session.guest.lastSeen) >= IDLE_TIMEOUT
                    || now - session.created >= MAX_AGE) {
                release(session.id);
            }
        }
    }

    /**
     * Release a match's route
     *
     * @param id the match id
     */
    synchronized void release(String id) {
        Session session = sessions.remove(id);
        if (session == null) {
            return;
        }
        for (Peer peer : List.of(session.host, session.guest)) {
            if (peer.endpoint != null) {
                endpoints.remove(peer.endpoint);
            }
        }
        log.info("TW04 relay released {} / {}; forwarded {} / {} packets", session.host.address.getHostAddress(),
                session.guest.address.getHostAddress(), session.host.forwarded, session.guest.forwarded);
    }
}
