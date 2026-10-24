package eu.eahub.services.core;

import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.AccountEntity;
import eu.eahub.entities.core.PersonaConnectionEntity;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.repositories.buddy.FeedbackRepository;
import eu.eahub.repositories.buddy.FeedbackTypeRepository;
import eu.eahub.repositories.core.AccountRepository;
import eu.eahub.repositories.core.PersonaConnectionRepository;
import eu.eahub.repositories.core.PersonaRepository;
import eu.eahub.services.server.SocketManager;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.HexUtils;
import eu.eahub.utils.PersonaUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonaServiceTest {

    private final PersonaRepository personaRepository = mock(PersonaRepository.class);
    private final PersonaConnectionRepository personaConnectionRepository = mock(PersonaConnectionRepository.class);
    private PersonaService personaService;
    private PersonaEntity persona;

    @BeforeEach
    void setUp() {
        RoomService roomService = mock(RoomService.class);
        PersonaUtils personaUtils = mock(PersonaUtils.class);
        personaService = new PersonaService(mock(AccountRepository.class), personaRepository, personaConnectionRepository,
                mock(SocketWriter.class), mock(SocketManager.class), mock(FeedbackRepository.class), mock(FeedbackTypeRepository.class),
                roomService, personaUtils);
        AccountEntity account = new AccountEntity();
        account.setLoc("en");
        persona = new PersonaEntity();
        persona.setId(1L);
        persona.setPers("player");
        persona.setAccount(account);
        when(personaRepository.findByPers("player")).thenReturn(Optional.of(persona));
        when(personaConnectionRepository.findByVersAndPersonaPersAndIsHostFalseAndEndTimeIsNull("TEST", "player")).thenReturn(List.of());
        when(personaUtils.getPersonaInfo(any(), any(), any())).thenReturn(Map.of());
    }

    private String persLkey() throws Exception {
        Socket socket = mock(Socket.class);
        when(socket.getInetAddress()).thenReturn(InetAddress.getLoopbackAddress());
        PersonaConnectionEntity personaConnectionEntity = new PersonaConnectionEntity();
        personaConnectionEntity.setVers("TEST");
        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setIdentifier("/127.0.0.1:1234");
        socketWrapper.setPersonaConnectionEntity(personaConnectionEntity);
        SocketData socketData = new SocketData("pers", "PERS=player\n", null);
        personaService.pers(socket, socketData, socketWrapper);
        assertEquals(socketWrapper.getLkey(), socketData.getOutputData().get("LKEY"));
        return socketWrapper.getLkey();
    }

    @Test
    void persIssuesDifferentLkeyForSameIdentifier() throws Exception {
        String firstLkey = persLkey();
        String secondLkey = persLkey();

        assertNotEquals(firstLkey, secondLkey);
        String identifierHash = HexUtils.formatHexString(MessageDigest.getInstance("SHA-256")
                .digest("/127.0.0.1:1234".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(identifierHash, firstLkey);
        assertEquals(64, firstLkey.length());
    }
}
