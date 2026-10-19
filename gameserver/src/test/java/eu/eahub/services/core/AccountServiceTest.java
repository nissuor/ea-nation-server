package eu.eahub.services.core;

import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.AccountEntity;
import eu.eahub.mappers.SocketMapper;
import eu.eahub.repositories.core.AccountRepository;
import eu.eahub.repositories.core.BlacklistRepository;
import eu.eahub.services.server.GameServerService;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.EmailUtils;
import eu.eahub.utils.PasswordUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "192.168.1.20"})
    void successfulLoginKeepsExactSocketIdentityForCleanupAndHostLookup(String address) throws Exception {
        PasswordUtils passwords = mock(PasswordUtils.class);
        AccountRepository accounts = mock(AccountRepository.class);
        GameServerService games = mock(GameServerService.class);
        SocketWriter writer = mock(SocketWriter.class);
        AccountService service = new AccountService(passwords, mock(SocketMapper.class), accounts,
                mock(BlacklistRepository.class), mock(PersonaService.class), games, writer, mock(EmailUtils.class));
        AccountEntity account = new AccountEntity();
        account.setName("test");
        account.setPass("hash");
        account.setPersonas(Set.of());
        when(accounts.findByName("test")).thenReturn(Optional.of(account));
        when(passwords.ssc2Decode("encoded")).thenReturn("sample");
        when(passwords.bCryptMatches("sample", "hash")).thenReturn(true);
        when(games.getNameByPort(31320)).thenReturn("PC_COACH06");
        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getByName(address));
        when(socket.getLocalPort()).thenReturn(31320);
        SocketWrapper wrapper = new SocketWrapper();
        wrapper.setSocket(socket);
        wrapper.setIdentifier("/" + address + ":50000");
        SocketData packet = new SocketData("auth", "NAME=test\nPASS=encoded\nVERS=PC/DEV", null);
        service.auth(socket, packet, wrapper);
        assertSame(account, wrapper.getAccountEntity());
        assertNotNull(wrapper.getPersonaConnectionEntity());
        assertEquals(wrapper.getIdentifier(), wrapper.getPersonaConnectionEntity().getAddress());
        assertEquals("auth", packet.getIdMessage());
        verify(writer).write(socket, packet);
    }
}
