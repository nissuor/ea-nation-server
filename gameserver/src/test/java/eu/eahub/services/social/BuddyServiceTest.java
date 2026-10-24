package eu.eahub.services.social;

import eu.eahub.dto.BuddySocketWrapper;
import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.entities.social.BuddyEntity;
import eu.eahub.repositories.buddy.BuddyRepository;
import eu.eahub.repositories.buddy.MessageRepository;
import eu.eahub.repositories.core.GameConnectionRepository;
import eu.eahub.repositories.core.PersonaRepository;
import eu.eahub.services.server.SocketManager;
import eu.eahub.steps.SocketWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.Socket;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuddyServiceTest {

    private final SocketWriter socketWriter = mock(SocketWriter.class);
    private final PersonaRepository personaRepository = mock(PersonaRepository.class);
    private final BuddyRepository buddyRepository = mock(BuddyRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private SocketManager socketManager;
    private BuddyService buddyService;
    private PersonaEntity persona;

    @BeforeEach
    void setUp() {
        socketManager = new SocketManager(mock(GameConnectionRepository.class));
        buddyService = new BuddyService(socketWriter, personaRepository, buddyRepository, messageRepository, socketManager);
        persona = new PersonaEntity();
        persona.setPers("player");
        when(personaRepository.findByPers("player")).thenReturn(Optional.of(persona));
        when(messageRepository.findUnacknowledgedMessagesByToPersona(any())).thenReturn(List.of());
    }

    @Test
    void authWithoutValidLkeyDoesNotBindPersona() {
        BuddySocketWrapper missingWrapper = new BuddySocketWrapper();
        buddyService.auth(mock(Socket.class), new SocketData("AUTH", "PROD=test\nVERS=test\nNAME=player\n", null), missingWrapper);
        assertNull(missingWrapper.getPersonaEntity());

        BuddySocketWrapper invalidWrapper = new BuddySocketWrapper();
        buddyService.auth(mock(Socket.class), new SocketData("AUTH", "PROD=test\nVERS=test\nLKEY=invalid\nNAME=player\n", null), invalidWrapper);
        assertNull(invalidWrapper.getPersonaEntity());
    }

    @Test
    void authWithIssuedLkeyBindsPersona() {
        socketManager.addSocket("/127.0.0.1:1234", mock(Socket.class));
        SocketWrapper socketWrapper = socketManager.getSocketWrapper("/127.0.0.1:1234");
        socketWrapper.setLkey("issued-lkey");
        socketWrapper.setPersonaEntity(persona);

        BuddySocketWrapper buddySocketWrapper = new BuddySocketWrapper();
        buddyService.auth(mock(Socket.class), new SocketData("AUTH", "PROD=test\nVERS=test\nLKEY=issued-lkey\nNAME=player\n", null), buddySocketWrapper);

        assertEquals(persona, buddySocketWrapper.getPersonaEntity());
    }

    @Test
    void raddWithBuddyListCreatesPendingEntry() {
        PersonaEntity other = new PersonaEntity();
        other.setPers("other");
        when(personaRepository.findByPers("other")).thenReturn(Optional.of(other));

        BuddySocketWrapper buddySocketWrapper = new BuddySocketWrapper();
        buddySocketWrapper.setPersonaEntity(persona);
        buddyService.radd(mock(Socket.class), new SocketData("RADD", "ID=1\nUSER=other\nLIST=B\n", null), buddySocketWrapper);

        ArgumentCaptor<BuddyEntity> captor = ArgumentCaptor.forClass(BuddyEntity.class);
        verify(buddyRepository).save(captor.capture());
        assertEquals("B", captor.getValue().getList());
        assertEquals("P", captor.getValue().getStatus());
    }
}
