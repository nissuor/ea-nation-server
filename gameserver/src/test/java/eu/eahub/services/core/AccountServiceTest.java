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
import eu.eahub.utils.SocketUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;

import static eu.eahub.services.server.GameServerService.PC_COACH06;
import static eu.eahub.services.server.GameServerService.PC_NFS06;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private static final int PORT = 31320;

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "192.168.1.20"})
    void authKeepsSocketIdentifierForCoach(String address) throws Exception {
        SocketWrapper socketWrapper = auth(PC_COACH06, address);
        assertEquals(socketWrapper.getIdentifier(), socketWrapper.getPersonaConnectionEntity().getAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "192.168.1.20"})
    void authKeepsLocalhostReplacementForOtherGames(String address) throws Exception {
        SocketWrapper socketWrapper = auth(PC_NFS06, address);
        String expected = SocketUtils.handleLocalhostIp("/" + address + ":50000");
        assertEquals(expected, socketWrapper.getPersonaConnectionEntity().getAddress());
    }

    private SocketWrapper auth(String gameName, String address) throws Exception {
        PasswordUtils passwordUtils = mock(PasswordUtils.class);
        AccountRepository accountRepository = mock(AccountRepository.class);
        GameServerService gameServerService = mock(GameServerService.class);
        SocketWriter socketWriter = mock(SocketWriter.class);
        AccountService accountService = new AccountService(passwordUtils, mock(SocketMapper.class), accountRepository,
                mock(BlacklistRepository.class), mock(PersonaService.class), gameServerService, socketWriter, mock(EmailUtils.class));

        AccountEntity accountEntity = new AccountEntity();
        accountEntity.setName("test");
        accountEntity.setPass("hash");
        accountEntity.setPersonas(Set.of());
        when(accountRepository.findByName("test")).thenReturn(Optional.of(accountEntity));
        when(passwordUtils.ssc2Decode("encoded")).thenReturn("sample");
        when(passwordUtils.bCryptMatches("sample", "hash")).thenReturn(true);
        when(gameServerService.getNameByPort(PORT)).thenReturn(gameName);

        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getByName(address));
        when(socket.getRemoteSocketAddress()).thenReturn(new InetSocketAddress(InetAddress.getByName(address), 50000));
        when(socket.getLocalPort()).thenReturn(PORT);
        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setSocket(socket);
        socketWrapper.setIdentifier("/" + address + ":50000");

        SocketData socketData = new SocketData("auth", "NAME=test\nPASS=encoded\nVERS=PC/DEV", null);
        accountService.auth(socket, socketData, socketWrapper);

        assertSame(accountEntity, socketWrapper.getAccountEntity());
        assertNotNull(socketWrapper.getPersonaConnectionEntity());
        assertEquals("auth", socketData.getIdMessage());
        verify(socketWriter).write(socket, socketData);
        return socketWrapper;
    }
}
