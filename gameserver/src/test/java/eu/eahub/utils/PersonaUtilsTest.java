package eu.eahub.utils;

import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.AccountEntity;
import eu.eahub.entities.core.PersonaConnectionEntity;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.repositories.core.GameRepository;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;

import static eu.eahub.services.server.GameServerService.PC_COACH06;
import static eu.eahub.services.server.GameServerService.PC_NFS06;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonaUtilsTest {

    private final GameRepository gameRepository = mock(GameRepository.class);
    private final PersonaUtils personaUtils = new PersonaUtils(gameRepository, null, null, null, null, null);

    @Test
    void getPersonaInfoKeepsGenericAddressAndFlag() throws Exception {
        SocketWrapper player = getSocketWrapper(1, "player", PC_NFS06);
        SocketWrapper opponent = getSocketWrapper(2, "opponent", PC_NFS06);

        // A and LA come from the given socket, every record has F=U
        Map<String, String> personaInfo = personaUtils.getPersonaInfo(player.getSocket(), opponent, null);
        assertEquals("127.0.0.1", personaInfo.get("A"));
        assertEquals("127.0.0.1", personaInfo.get("LA"));
        assertEquals("U", personaInfo.get("F"));
        assertEquals("opponent", personaInfo.get("N"));
    }

    @Test
    void getCoachPersonaInfoFlagsOnlyRecipientsOwnRecord() throws Exception {
        List<SocketWrapper> players = List.of(getSocketWrapper(1, "jack041", PC_COACH06), getSocketWrapper(2, "Labeo", PC_COACH06));

        // The game copies every F=U record into its own persona, whatever the order of the records
        for (SocketWrapper recipient : players) {
            for (List<SocketWrapper> order : List.of(players, players.reversed())) {
                String localName = recipient.getPersonaEntity().getPers();
                for (SocketWrapper player : order) {
                    Map<String, String> personaInfo = personaUtils.getCoachPersonaInfo(recipient.getSocket(), player, null);
                    String address = player.getSocket().getInetAddress().getHostAddress();
                    assertEquals(address, personaInfo.get("A"));
                    assertEquals(address, personaInfo.get("LA"));
                    assertEquals(player == recipient ? "U" : "", personaInfo.get("F"));
                    if ("U".equals(personaInfo.get("F"))) {
                        localName = personaInfo.get("N");
                    }
                }
                assertEquals(recipient.getPersonaEntity().getPers(), localName);
            }
        }
    }

    private SocketWrapper getSocketWrapper(long id, String pers, String vers) throws Exception {
        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getByName("127.0.0." + id));

        AccountEntity accountEntity = new AccountEntity();
        accountEntity.setId(id);
        accountEntity.setName(pers);
        accountEntity.setLoc("enUS");

        PersonaEntity personaEntity = new PersonaEntity();
        personaEntity.setId(id);
        personaEntity.setPers(pers);

        PersonaConnectionEntity personaConnectionEntity = new PersonaConnectionEntity();
        personaConnectionEntity.setId(id);
        personaConnectionEntity.setVers(vers);
        when(gameRepository.findCurrentGameOfPersona(id)).thenReturn(List.of());

        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setSocket(socket);
        socketWrapper.setAccountEntity(accountEntity);
        socketWrapper.setPersonaEntity(personaEntity);
        socketWrapper.setPersonaConnectionEntity(personaConnectionEntity);
        return socketWrapper;
    }
}
