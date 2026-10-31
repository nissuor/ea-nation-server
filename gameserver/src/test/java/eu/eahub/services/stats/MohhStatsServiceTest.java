package eu.eahub.services.stats;

import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.GameConnectionEntity;
import eu.eahub.entities.core.GameEntity;
import eu.eahub.entities.core.PersonaConnectionEntity;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.mappers.SocketMapper;
import eu.eahub.repositories.core.GameConnectionRepository;
import eu.eahub.repositories.stats.MohhGameReportRepository;
import eu.eahub.repositories.stats.MohhPersonaStatsRepository;
import eu.eahub.services.server.GameServerService;
import eu.eahub.steps.SocketWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MohhStatsServiceTest {

    private final MohhPersonaStatsRepository mohhPersonaStatsRepository = mock(MohhPersonaStatsRepository.class);
    private final GameConnectionRepository gameConnectionRepository = mock(GameConnectionRepository.class);
    private final MohhGameReportRepository mohhGameReportRepository = mock(MohhGameReportRepository.class);
    private MohhStatsService mohhStatsService;

    @BeforeEach
    void setUp() {
        mohhStatsService = new MohhStatsService(mock(SocketMapper.class), mock(SocketWriter.class), mohhPersonaStatsRepository, gameConnectionRepository, mohhGameReportRepository, mock(GameServerService.class));
    }

    private static PersonaEntity persona(long id, String pers) {
        PersonaEntity personaEntity = new PersonaEntity();
        personaEntity.setId(id);
        personaEntity.setPers(pers);
        return personaEntity;
    }

    private static GameConnectionEntity gameConnection(PersonaEntity personaEntity, long gameId) {
        GameEntity gameEntity = new GameEntity();
        gameEntity.setId(gameId);
        gameEntity.setVers("PSP/MOH07");
        PersonaConnectionEntity personaConnectionEntity = new PersonaConnectionEntity();
        personaConnectionEntity.setPersona(personaEntity);
        personaConnectionEntity.setVers("PSP/MOH07");
        GameConnectionEntity gameConnectionEntity = new GameConnectionEntity();
        gameConnectionEntity.setGame(gameEntity);
        gameConnectionEntity.setPersonaConnection(personaConnectionEntity);
        return gameConnectionEntity;
    }

    private static SocketWrapper loggedIn(PersonaEntity personaEntity) {
        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setPersonaEntity(personaEntity);
        return socketWrapper;
    }

    @Test
    void reportNamingAnotherPersonaIsNotCredited() {
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);
        PersonaEntity otherPersona = persona(2, "other");
        when(gameConnectionRepository.findMatchingGameConnections("other", startTime, false)).thenReturn(List.of(gameConnection(otherPersona, 1)));

        mohhStatsService.rank(new SocketData("rank", "REPT=other\tWHEN=2025.1.1-12:00:00\tRNK=1", null), loggedIn(persona(1, "player")));

        verify(mohhGameReportRepository, never()).save(any());
        verify(mohhPersonaStatsRepository, never()).save(any());
    }

    @Test
    void reportNamingOwnPersonaIsCredited() {
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);
        PersonaEntity ownPersona = persona(1, "player");
        when(gameConnectionRepository.findMatchingGameConnections("player", startTime, false)).thenReturn(List.of(gameConnection(ownPersona, 1)));

        mohhStatsService.rank(new SocketData("rank", "REPT=player\tWHEN=2025.1.1-12:00:00\tRNK=1", null), loggedIn(ownPersona));

        verify(mohhGameReportRepository).save(any());
    }
}
