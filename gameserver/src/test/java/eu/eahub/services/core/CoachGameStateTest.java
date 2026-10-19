package eu.eahub.services.core;

import eu.eahub.dto.*;
import eu.eahub.entities.core.*;
import eu.eahub.repositories.core.*;
import eu.eahub.services.server.*;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.GameUtils;
import org.junit.jupiter.api.Test;
import java.net.Socket;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoachGameStateTest {
    @Test void combinedUpdateIsCompleteBeforeEitherPlayerReceivesIt() {
        GameRepository games = mock(GameRepository.class);
        GameConnectionRepository connections = mock(GameConnectionRepository.class);
        SocketManager sockets = mock(SocketManager.class);
        GameServerService servers = mock(GameServerService.class);
        when(servers.isP2P("PC_COACH06")).thenReturn(true);
        SocketWriter writer = mock(SocketWriter.class);
        GameUtils info = mock(GameUtils.class);
        GameService service = new GameService(games, connections, null, null, null, null, null,
                servers, null, null, null, writer, sockets, null, null, info);
        GameEntity game = new GameEntity();
        game.setId(34L); game.setVers("PC_COACH06"); game.setParams("old"); game.setSysflags("0");
        List<GameConnectionEntity> participants = new ArrayList<>();
        List<SocketWrapper> wrappers = new ArrayList<>();
        for (long id : new long[]{1,2}) {
            PersonaConnectionEntity person = new PersonaConnectionEntity();
            person.setId(id); person.setVers("PC_COACH06");
            SocketWrapper wrapper = new SocketWrapper();
            wrapper.setPersonaConnectionEntity(person); wrapper.setSocket(mock(Socket.class));
            GameConnectionEntity connection = new GameConnectionEntity();
            connection.setGame(game); connection.setPersonaConnection(person);
            participants.add(connection); wrappers.add(wrapper);
            when(connections.findByPersonaConnectionIdAndEndTimeIsNull(id)).thenReturn(Optional.of(connection));
            when(sockets.getSocketWrapperByPersonaConnectionId(id)).thenReturn(wrapper);
        }
        when(connections.findByGameIdAndEndTimeIsNull(34L)).thenReturn(participants);
        String settings = "^\u0084\u0085\u0080";
        when(info.getGameInfo(game)).thenAnswer(call -> {
            assertEquals("327688", game.getSysflags());
            assertEquals("GS=ready", game.getParams());
            assertEquals("1", wrappers.getFirst().getUserflags());
            assertEquals(settings, wrappers.getFirst().getUserparams());
            return Map.of("IDENT", "34", "PARAMS", game.getParams());
        });
        SocketWrapper host = wrappers.getFirst();
        service.gset(host.getSocket(), new SocketData("gset",
                "USERFLAGS=1\nUSERPARAMS="+settings+"\nSYSFLAGS=327688\nPARAMS=GS=ready\0", null), host);
        verify(games).save(game);
        for (SocketWrapper wrapper : wrappers) {
            verify(writer).write(eq(wrapper.getSocket()), argThat(packet -> "+mgm".equals(packet.getIdMessage())
                    && "GS=ready".equals(packet.getOutputData().get("PARAMS"))));
        }
        verify(writer).write(eq(host.getSocket()), argThat(packet -> "gset".equals(packet.getIdMessage())));
    }
}
