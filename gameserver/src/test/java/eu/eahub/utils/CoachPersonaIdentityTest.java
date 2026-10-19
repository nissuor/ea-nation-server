package eu.eahub.utils;

import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.*;
import eu.eahub.repositories.core.GameRepository;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoachPersonaIdentityTest {
    @Test void updatesForTwoPlayersPreserveEachRecipientsIdentity() throws Exception {
        GameRepository games = mock(GameRepository.class);
        PersonaUtils utils = new PersonaUtils(games, null, null, null, null, null);
        List<SocketWrapper> players = new ArrayList<>();
        for (int i=0; i<2; i++) {
            SocketWrapper player = new SocketWrapper();
            Socket socket = mock(Socket.class);
            when(socket.getInetAddress()).thenReturn(InetAddress.getByName("127.0.0."+(i+1)));
            player.setSocket(socket);
            AccountEntity account = new AccountEntity();
            account.setId((long)i+1); account.setName(i==0 ? "jack" : "labeo"); account.setLoc("enUS");
            player.setAccountEntity(account);
            PersonaEntity persona = new PersonaEntity();
            persona.setId((long)i+1); persona.setPers(i==0 ? "jack041" : "Labeo");
            player.setPersonaEntity(persona);
            PersonaConnectionEntity connection = new PersonaConnectionEntity();
            connection.setId((long)i+1); connection.setVers("PC_COACH06");
            player.setPersonaConnectionEntity(connection);
            when(games.findCurrentGameOfPersona(connection.getId())).thenReturn(List.of());
            players.add(player);
        }
        // Model the client's +usr behavior: only U records replace the local persona.
        // Exercise both orders, including the opponent's record arriving last.
        for (SocketWrapper recipient : players) {
            for (List<SocketWrapper> order : List.of(players, players.reversed())) {
                String localName = recipient.getPersonaEntity().getPers();
                for (SocketWrapper subject : order) {
                    Map<String,String> update = utils.getPersonaInfo(recipient.getSocket(), subject, null);
                    assertEquals(subject.getSocket().getInetAddress().getHostAddress(), update.get("A"));
                    assertEquals(subject.getSocket().getInetAddress().getHostAddress(), update.get("LA"));
                    assertEquals(subject==recipient ? "U" : "", update.get("F"));
                    if (update.get("F").contains("U")) localName = update.get("N");
                }
                assertEquals(recipient.getPersonaEntity().getPers(), localName);
            }
        }
    }
}
