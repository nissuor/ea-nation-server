package eu.eahub.services.core;

import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.AccountEntity;
import eu.eahub.entities.core.GameConnectionEntity;
import eu.eahub.entities.core.GameEntity;
import eu.eahub.entities.core.PersonaConnectionEntity;
import eu.eahub.repositories.core.AccountRepository;
import eu.eahub.repositories.core.BlacklistRepository;
import eu.eahub.repositories.core.GameConnectionRepository;
import eu.eahub.repositories.core.GameRepository;
import eu.eahub.services.server.GameServerService;
import eu.eahub.services.server.SocketManager;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.GameUtils;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static eu.eahub.services.server.GameServerService.PC_COACH06;
import static eu.eahub.services.server.GameServerService.PC_NFS06;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceTest {

    private static final String USERPARAMS = "^\u0084\u0085\u0080";

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GameConnectionRepository gameConnectionRepository = mock(GameConnectionRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final GameServerService gameServerService = mock(GameServerService.class);
    private final SocketWriter socketWriter = mock(SocketWriter.class);
    private final SocketManager socketManager = mock(SocketManager.class);
    private final GameUtils gameUtils = mock(GameUtils.class);
    private final GameService gameService = new GameService(gameRepository, gameConnectionRepository, null, accountRepository,
            mock(BlacklistRepository.class), null, null, gameServerService, null, null, null, socketWriter, socketManager, null, null, gameUtils);

    @Test
    void coachGsetIsAppliedBeforeBroadcast() {
        when(gameServerService.isP2P(PC_COACH06)).thenReturn(true);
        GameEntity gameEntity = new GameEntity();
        gameEntity.setId(34L);
        gameEntity.setVers(PC_COACH06);
        gameEntity.setParams("old");
        gameEntity.setSysflags("0");

        List<GameConnectionEntity> gameConnections = new ArrayList<>();
        List<SocketWrapper> socketWrappers = new ArrayList<>();
        for (long id = 1; id <= 2; id++) {
            SocketWrapper socketWrapper = getSocketWrapper(id, PC_COACH06);
            GameConnectionEntity gameConnectionEntity = new GameConnectionEntity();
            gameConnectionEntity.setGame(gameEntity);
            gameConnectionEntity.setPersonaConnection(socketWrapper.getPersonaConnectionEntity());
            gameConnections.add(gameConnectionEntity);
            socketWrappers.add(socketWrapper);
            when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(id)).thenReturn(Optional.of(gameConnectionEntity));
            when(socketManager.getSocketWrapperByPersonaConnectionId(id)).thenReturn(socketWrapper);
        }
        when(gameConnectionRepository.findByGameIdAndEndTimeIsNull(34L)).thenReturn(gameConnections);

        SocketWrapper host = socketWrappers.getFirst();
        // Every field must be updated before the first broadcast
        when(gameUtils.getGameInfo(gameEntity)).thenAnswer(invocation -> {
            assertEquals("327688", gameEntity.getSysflags());
            assertEquals("GS=ready", gameEntity.getParams());
            assertEquals("1", host.getUserflags());
            assertEquals(USERPARAMS, host.getUserparams());
            return Map.of("IDENT", "34", "PARAMS", gameEntity.getParams());
        });

        SocketData socketData = new SocketData("gset", "USERFLAGS=1\nUSERPARAMS=" + USERPARAMS + "\nSYSFLAGS=327688\nPARAMS=GS=ready\0", null);
        gameService.gset(host.getSocket(), socketData, host);

        verify(gameRepository).save(gameEntity);
        for (SocketWrapper socketWrapper : socketWrappers) {
            verify(socketWriter).write(eq(socketWrapper.getSocket()), argThat(data -> "+mgm".equals(data.getIdMessage())
                    && "GS=ready".equals(data.getOutputData().get("PARAMS"))));
        }
        verify(socketWriter).write(eq(host.getSocket()), argThat(data -> "gset".equals(data.getIdMessage())));
    }

    @Test
    void gjoiKeepsCoachUserparams() throws Exception {
        assertEquals(USERPARAMS, gjoi(PC_COACH06).getUserparams());
    }

    @Test
    void gjoiKeepsGenericParsingForOtherGames() throws Exception {
        // \R splits on 0x85
        assertEquals("^\u0084", gjoi(PC_NFS06).getUserparams());
    }

    private SocketWrapper gjoi(String vers) throws Exception {
        SocketWrapper socketWrapper = getSocketWrapper(1, vers);
        when(socketWrapper.getSocket().getInetAddress()).thenReturn(InetAddress.getLoopbackAddress());
        AccountEntity accountEntity = new AccountEntity();
        accountEntity.setId(1L);
        socketWrapper.setAccountEntity(accountEntity);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(accountEntity));

        SocketData socketData = new SocketData("gjoi", "NAME=game\nUSERPARAMS=" + USERPARAMS + "\0", null);
        gameService.gjoi(socketWrapper.getSocket(), socketData, socketWrapper);
        return socketWrapper;
    }

    private SocketWrapper getSocketWrapper(long id, String vers) {
        PersonaConnectionEntity personaConnectionEntity = new PersonaConnectionEntity();
        personaConnectionEntity.setId(id);
        personaConnectionEntity.setVers(vers);

        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setPersonaConnectionEntity(personaConnectionEntity);
        socketWrapper.setSocket(mock(Socket.class));
        return socketWrapper;
    }
}
