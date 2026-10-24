package eu.eahub.services.core;

import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.GameConnectionEntity;
import eu.eahub.entities.core.GameEntity;
import eu.eahub.entities.core.PersonaConnectionEntity;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.mappers.SocketMapper;
import eu.eahub.repositories.core.AccountRepository;
import eu.eahub.repositories.core.BlacklistRepository;
import eu.eahub.repositories.core.GameConnectionRepository;
import eu.eahub.repositories.core.GameRepository;
import eu.eahub.repositories.core.PersonaConnectionRepository;
import eu.eahub.repositories.core.UserSetMemberRepository;
import eu.eahub.repositories.core.UserSetRepository;
import eu.eahub.services.server.GameServerService;
import eu.eahub.services.server.SocketManager;
import eu.eahub.services.stats.MohhStatsService;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.GameUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceTest {

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GameConnectionRepository gameConnectionRepository = mock(GameConnectionRepository.class);
    private final SocketMapper socketMapper = mock(SocketMapper.class);
    private final GameServerService gameServerService = mock(GameServerService.class);
    private final SocketWriter socketWriter = mock(SocketWriter.class);
    private final SocketManager socketManager = mock(SocketManager.class);
    private final GameUtils gameUtils = mock(GameUtils.class);
    private GameService gameService;
    private GameEntity game;

    @BeforeEach
    void setUp() {
        gameService = new GameService(gameRepository, gameConnectionRepository, mock(PersonaConnectionRepository.class),
                mock(AccountRepository.class), mock(BlacklistRepository.class), socketMapper, mock(PersonaService.class),
                gameServerService, mock(RoomService.class), mock(UserSetService.class), mock(MohhStatsService.class),
                socketWriter, socketManager, mock(UserSetRepository.class), mock(UserSetMemberRepository.class), gameUtils);
        game = game(10);
    }

    private static GameEntity game(long id) {
        GameEntity game = new GameEntity();
        game.setId(id);
        game.setVers("TEST");
        game.setName("game");
        game.setSysflags("0");
        game.setMaxsize(4);
        game.setGameConnections(new HashSet<>());
        return game;
    }

    private static PersonaConnectionEntity personaConnection(long id, String vers) {
        PersonaConnectionEntity personaConnection = new PersonaConnectionEntity();
        personaConnection.setId(id);
        personaConnection.setVers(vers);
        return personaConnection;
    }

    private static GameConnectionEntity connection(GameEntity game, PersonaConnectionEntity personaConnection, boolean host) {
        GameConnectionEntity connection = new GameConnectionEntity();
        connection.setGame(game);
        connection.setPersonaConnection(personaConnection);
        connection.setHost(host);
        return connection;
    }

    private static SocketWrapper wrapper(PersonaConnectionEntity personaConnection) {
        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setPersonaConnectionEntity(personaConnection);
        return socketWrapper;
    }

    @Test
    void gpssWithoutPersonaConnectionDoesNotSetFlags() {
        SocketWrapper socketWrapper = new SocketWrapper();

        gameService.gpss(mock(Socket.class), new SocketData("gpss", "STATUS=A", null), socketWrapper);

        assertFalse(socketWrapper.getIsGps().get());
        assertFalse(socketWrapper.getIsHosting().get());
    }

    @Test
    void gpssWithPersonaConnectionSetsFlags() {
        SocketWrapper socketWrapper = wrapper(personaConnection(1, "TEST"));

        gameService.gpss(mock(Socket.class), new SocketData("gpss", "STATUS=A", null), socketWrapper);

        assertTrue(socketWrapper.getIsGps().get());
        assertFalse(socketWrapper.getIsHosting().get());
    }

    @Test
    void joinWithMissingPassDoesNotJoinPasswordedGame() {
        game.setPass("secret");
        SocketWrapper socketWrapper = wrapper(personaConnection(1, "TEST"));

        gameService.joinGame(mock(Socket.class), new SocketData("gjoi", "IDENT=10", null), socketWrapper, game);

        ArgumentCaptor<SocketData> captor = ArgumentCaptor.forClass(SocketData.class);
        verify(socketWriter, atLeastOnce()).write(any(), captor.capture());
        assertEquals("gjoipass", captor.getValue().getIdMessage());
        verify(gameConnectionRepository, never()).save(any());
    }

    @Test
    void joinWithEmptyPassDoesNotJoinPasswordedGame() {
        game.setPass("secret");
        SocketWrapper socketWrapper = wrapper(personaConnection(1, "TEST"));

        gameService.joinGame(mock(Socket.class), new SocketData("gjoi", "IDENT=10\nPASS=", null), socketWrapper, game);

        verify(gameConnectionRepository, never()).save(any());
    }

    @Test
    void joinWithCorrectPassJoinsPasswordedGame() {
        game.setPass("secret");
        SocketWrapper socketWrapper = wrapper(personaConnection(1, "TEST"));
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L)).thenReturn(Optional.empty());
        when(gameUtils.getGameInfo(game)).thenReturn(Map.of());

        gameService.joinGame(mock(Socket.class), new SocketData("gjoi", "IDENT=10\nPASS=secret", null), socketWrapper, game);

        verify(gameConnectionRepository).save(any(GameConnectionEntity.class));
    }

    @Test
    void joinWithoutPassStillJoinsOpenGame() {
        SocketWrapper socketWrapper = wrapper(personaConnection(1, "TEST"));
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L)).thenReturn(Optional.empty());
        when(gameUtils.getGameInfo(game)).thenReturn(Map.of());

        gameService.joinGame(mock(Socket.class), new SocketData("gjoi", "IDENT=10", null), socketWrapper, game);

        verify(gameConnectionRepository).save(any(GameConnectionEntity.class));
    }

    @Test
    void gdelByNonHostDoesNotEndGame() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, false)));

        gameService.gdel(mock(Socket.class), new SocketData("gdel", "", null), wrapper(personaConnection));

        verify(gameRepository, never()).findCurrentGameOfPersona(1L);
        verify(gameRepository, never()).save(any());
    }

    @Test
    void gdelByHostEndsGame() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, true)));
        when(gameRepository.findCurrentGameOfPersona(1L)).thenReturn(List.of(game));

        gameService.gdel(mock(Socket.class), new SocketData("gdel", "", null), wrapper(personaConnection));

        verify(gameRepository).save(game);
        assertNotNull(game.getEndTime());
    }

    @Test
    void gstaByNonHostDoesNotStartGame() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(gameServerService.isP2P("TEST")).thenReturn(true);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, false)));

        gameService.gsta(mock(Socket.class), new SocketData("gsta", "", null), wrapper(personaConnection));

        assertFalse(game.isStarted());
        verify(gameRepository, never()).save(any());
    }

    @Test
    void gstaByHostStartsGame() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(gameServerService.isP2P("TEST")).thenReturn(true);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, true)));
        when(gameConnectionRepository.findByGameIdAndEndTimeIsNull(10L)).thenReturn(List.of());

        gameService.gsta(mock(Socket.class), new SocketData("gsta", "", null), wrapper(personaConnection));

        assertTrue(game.isStarted());
        verify(gameRepository).save(game);
    }

    @Test
    void gsetByNonHostDoesNotChangeSysflags() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(gameServerService.isP2P("TEST")).thenReturn(true);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, false)));

        gameService.gset(mock(Socket.class), new SocketData("gset", "SYSFLAGS=5", null), wrapper(personaConnection));

        assertEquals("0", game.getSysflags());
        verify(gameRepository, never()).save(any());
    }

    @Test
    void gsetByHostChangesSysflags() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(gameServerService.isP2P("TEST")).thenReturn(true);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, true)));
        when(gameUtils.getGameInfo(game)).thenReturn(Map.of());

        gameService.gset(mock(Socket.class), new SocketData("gset", "SYSFLAGS=5", null), wrapper(personaConnection));

        assertEquals("5", game.getSysflags());
        verify(gameRepository).save(game);
    }

    @Test
    void gsetWithNonNumericSysflagsDoesNotChangeSysflags() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(gameServerService.isP2P("TEST")).thenReturn(true);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, true)));

        gameService.gset(mock(Socket.class), new SocketData("gset", "SYSFLAGS=abc", null), wrapper(personaConnection));

        assertEquals("0", game.getSysflags());
        verify(gameRepository, never()).save(any());
    }

    @Test
    void gsetKickForPlayerOutsideGameDoesNotKick() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        PersonaConnectionEntity targetConnection = personaConnection(99, "TEST");
        PersonaEntity targetPersona = new PersonaEntity();
        targetPersona.setId(2L);
        targetPersona.setPers("target");
        SocketWrapper targetWrapper = wrapper(targetConnection);
        targetWrapper.setPersonaEntity(targetPersona);
        Socket targetSocket = mock(Socket.class);
        targetWrapper.setSocket(targetSocket);
        when(gameServerService.isP2P("TEST")).thenReturn(true);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, true)));
        when(gameConnectionRepository.findByGameIdAndEndTimeIsNull(10L)).thenReturn(List.of());
        when(socketManager.getSocketWrapperByVers("TEST")).thenReturn(List.of(targetWrapper));

        gameService.gset(mock(Socket.class), new SocketData("gset", "KICK=target", null), wrapper(personaConnection));

        verify(socketWriter, never()).write(eq(targetSocket), any(SocketData.class));
    }

    @Test
    void gsetKickForPlayerInSameGameKicks() {
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        PersonaConnectionEntity targetConnection = personaConnection(99, "TEST");
        PersonaEntity targetPersona = new PersonaEntity();
        targetPersona.setId(2L);
        targetPersona.setPers("target");
        SocketWrapper targetWrapper = wrapper(targetConnection);
        targetWrapper.setPersonaEntity(targetPersona);
        Socket targetSocket = mock(Socket.class);
        targetWrapper.setSocket(targetSocket);
        when(gameServerService.isP2P("TEST")).thenReturn(true);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L))
                .thenReturn(Optional.of(connection(game, personaConnection, true)));
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(99L)).thenReturn(Optional.empty());
        when(gameConnectionRepository.findByGameIdAndEndTimeIsNull(10L))
                .thenReturn(List.of(connection(game, targetConnection, false)));
        when(socketManager.getSocketWrapperByVers("TEST")).thenReturn(List.of(targetWrapper));

        gameService.gset(mock(Socket.class), new SocketData("gset", "KICK=target", null), wrapper(personaConnection));

        ArgumentCaptor<SocketData> captor = ArgumentCaptor.forClass(SocketData.class);
        verify(socketWriter).write(eq(targetSocket), captor.capture());
        assertEquals("+kik", captor.getValue().getIdMessage());
    }

    @Test
    void gcreWithNonNumericSysflagsDoesNotCreateGame() {
        GameEntity newGame = game(0);
        newGame.setSysflags("abc");
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        when(socketMapper.toGameEntity(anyString(), anyString())).thenReturn(newGame);

        gameService.gcre(mock(Socket.class), new SocketData("gcre", "SYSFLAGS=abc", null), wrapper(personaConnection));

        verify(gameRepository, never()).save(any());
    }

    @Test
    void gcreWithNumericSysflagsCreatesGame() {
        GameEntity newGame = game(0);
        PersonaConnectionEntity personaConnection = personaConnection(1, "TEST");
        SocketWrapper socketWrapper = wrapper(personaConnection);
        PersonaEntity persona = new PersonaEntity();
        persona.setId(1L);
        persona.setPers("player");
        socketWrapper.setPersonaEntity(persona);
        when(socketMapper.toGameEntity(anyString(), anyString())).thenReturn(newGame);
        when(gameRepository.existsByNameAndVersAndEndTimeIsNull("game", "TEST")).thenReturn(false);
        when(gameConnectionRepository.findByPersonaConnectionIdAndEndTimeIsNull(1L)).thenReturn(Optional.empty());
        when(gameUtils.getGameInfo(newGame)).thenReturn(Map.of());

        gameService.gcre(mock(Socket.class), new SocketData("gcre", "SYSFLAGS=0", null), socketWrapper);

        verify(gameRepository).save(newGame);
    }
}
