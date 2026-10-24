package eu.eahub.services.core;

import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.entities.core.UserSetEntity;
import eu.eahub.repositories.core.GameConnectionRepository;
import eu.eahub.repositories.core.UserSetMemberRepository;
import eu.eahub.repositories.core.UserSetRepository;
import eu.eahub.services.server.SocketManager;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.PersonaUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSetServiceTest {

    private final UserSetRepository userSetRepository = mock(UserSetRepository.class);
    private final UserSetMemberRepository userSetMemberRepository = mock(UserSetMemberRepository.class);
    private final SocketWriter socketWriter = mock(SocketWriter.class);
    private UserSetService userSetService;
    private PersonaEntity owner;
    private UserSetEntity userSet;

    @BeforeEach
    void setUp() {
        userSetService = new UserSetService(userSetRepository, userSetMemberRepository,
                mock(GameConnectionRepository.class), socketWriter, mock(SocketManager.class),
                mock(PersonaUtils.class), mock(RoomService.class));
        owner = persona(1, "owner");
        userSet = userSet(10, owner);
        when(userSetRepository.findById(10L)).thenReturn(Optional.of(userSet));
    }

    private static PersonaEntity persona(long id, String pers) {
        PersonaEntity persona = new PersonaEntity();
        persona.setId(id);
        persona.setPers(pers);
        return persona;
    }

    private static UserSetEntity userSet(long id, PersonaEntity owner) {
        UserSetEntity userSet = new UserSetEntity();
        userSet.setId(id);
        userSet.setVers("TEST");
        userSet.setName("set");
        userSet.setOwner(owner);
        userSet.setMembers(new HashSet<>());
        return userSet;
    }

    private static SocketWrapper wrapper(PersonaEntity persona, Long userSetId) {
        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setPersonaEntity(persona);
        socketWrapper.setUserSetId(userSetId);
        return socketWrapper;
    }

    @Test
    void udelByNonOwnerDoesNotDeleteUserSet() {
        SocketWrapper socketWrapper = wrapper(persona(2, "other"), 10L);

        userSetService.udel(mock(Socket.class), new SocketData("udel", "", null), socketWrapper);

        assertNull(userSet.getEndTime());
        verify(userSetRepository, never()).save(any());
    }

    @Test
    void udelByOwnerDeletesUserSet() {
        SocketWrapper socketWrapper = wrapper(owner, 10L);
        when(userSetMemberRepository.findByUserSetIdAndEndTimeIsNull(10L)).thenReturn(List.of());

        userSetService.udel(mock(Socket.class), new SocketData("udel", "", null), socketWrapper);

        assertNotNull(userSet.getEndTime());
        verify(userSetRepository).save(userSet);
    }

    @Test
    void uadmByNonOwnerDoesNotUpdateUserSet() {
        SocketWrapper socketWrapper = wrapper(persona(2, "other"), 10L);

        userSetService.uadm(mock(Socket.class), new SocketData("uadm", "DESC=changed", null), socketWrapper);

        assertNull(userSet.getDescription());
        verify(userSetRepository, never()).save(any());
    }

    @Test
    void uadmByOwnerUpdatesUserSet() {
        SocketWrapper socketWrapper = wrapper(owner, 10L);

        userSetService.uadm(mock(Socket.class), new SocketData("uadm", "DESC=changed", null), socketWrapper);

        assertEquals("changed", userSet.getDescription());
        verify(userSetRepository).save(userSet);
    }
}
