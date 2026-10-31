package eu.eahub.services.stats;

import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.entities.core.GameConnectionEntity;
import eu.eahub.entities.core.GameEntity;
import eu.eahub.entities.core.PersonaConnectionEntity;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.repositories.core.GameConnectionRepository;
import eu.eahub.repositories.stats.NhlGameReportRepository;
import eu.eahub.repositories.stats.NhlPersonaStatsRepository;
import eu.eahub.steps.SocketWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NhlStatsServiceTest {

    private final NhlPersonaStatsRepository nhlPersonaStatsRepository = mock(NhlPersonaStatsRepository.class);
    private final NhlGameReportRepository nhlGameReportRepository = mock(NhlGameReportRepository.class);
    private final GameConnectionRepository gameConnectionRepository = mock(GameConnectionRepository.class);
    private NhlStatsService nhlStatsService;

    @BeforeEach
    void setUp() {
        nhlStatsService = new NhlStatsService(nhlPersonaStatsRepository, nhlGameReportRepository, gameConnectionRepository, mock(SocketWriter.class));
        when(nhlGameReportRepository.existsByGameConnectionId(anyLong())).thenReturn(false);
        when(nhlPersonaStatsRepository.findByPersonaIdAndVers(anyLong(), anyString())).thenReturn(null);
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
        gameEntity.setVers("PSP/NHL07");
        PersonaConnectionEntity personaConnectionEntity = new PersonaConnectionEntity();
        personaConnectionEntity.setPersona(personaEntity);
        personaConnectionEntity.setVers("PSP/NHL07");
        GameConnectionEntity gameConnectionEntity = new GameConnectionEntity();
        gameConnectionEntity.setId(personaEntity.getId());
        gameConnectionEntity.setGame(gameEntity);
        gameConnectionEntity.setPersonaConnection(personaConnectionEntity);
        return gameConnectionEntity;
    }

    private static SocketWrapper loggedIn(PersonaEntity personaEntity) {
        SocketWrapper socketWrapper = new SocketWrapper();
        socketWrapper.setPersonaEntity(personaEntity);
        return socketWrapper;
    }

    private static String report(String name0, String name1) {
        StringBuilder input = new StringBuilder();
        input.append("WHEN=2025.1.1-12:00:00\t");
        input.append("NAME0=").append(name0).append("\t");
        input.append("NAME1=").append(name1).append("\t");
        String[] playerFields = {"SCORE", "DSCORE", "HITS", "SHOTS", "PENMIN", "PPG", "PPO", "SHG", "TEAM", "HOME", "DISC", "QUIT", "CHEAT", "WEIGHT"};
        for (String field : playerFields) {
            input.append(field).append("0=0\t");
            input.append(field).append("1=0\t");
        }
        input.append("SCORE0=2\tSCORE1=1\t");
        String[] commonFields = {"VENUE", "TYPE", "PNUM", "PLEN", "OT", "TIME", "DTIME", "SKIL", "TID", "TMID"};
        for (String field : commonFields) {
            input.append(field).append("=0\t");
        }
        input.append("RNK=1");
        return input.toString();
    }

    @Test
    void reportNamingOtherPersonasIsNotCredited() {
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);
        when(gameConnectionRepository.findMatchingGameConnections("other0", startTime, true)).thenReturn(List.of(gameConnection(persona(2, "other0"), 1)));
        when(gameConnectionRepository.findMatchingGameConnections("other1", startTime, true)).thenReturn(List.of(gameConnection(persona(3, "other1"), 1)));

        nhlStatsService.rank(new SocketData("rank", report("other0", "other1"), null), loggedIn(persona(1, "player")));

        verify(nhlGameReportRepository, never()).save(any());
        verify(nhlPersonaStatsRepository, never()).save(any());
    }

    @Test
    void reportNamingPersonaFromAnotherMatchIsNotCredited() {
        LocalDateTime startTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);
        PersonaEntity ownPersona = persona(1, "player");
        when(gameConnectionRepository.findMatchingGameConnections("player", startTime, true)).thenReturn(List.of(gameConnection(ownPersona, 1)));
        when(gameConnectionRepository.findMatchingGameConnections("other", startTime, true)).thenReturn(List.of(gameConnection(persona(2, "other"), 2)));

        nhlStatsService.rank(new SocketData("rank", report("player", "other"), null), loggedIn(ownPersona));

        verify(nhlGameReportRepository, times(1)).save(any());
        verify(nhlPersonaStatsRepository, times(1)).save(any());
    }
}
