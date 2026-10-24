package eu.eahub.services.stats;

import eu.eahub.dto.SocketData;
import eu.eahub.steps.SocketWriter;
import org.junit.jupiter.api.Test;

import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CoachCareerServiceTest {

    private final SocketWriter socketWriter = mock(SocketWriter.class);
    private final CoachCareerService coachCareerService = new CoachCareerService(socketWriter);
    private final Socket socket = mock(Socket.class);

    @Test
    void slstLoadsCareerStatbook() {
        SocketData socketData = new SocketData("slst", "", null);
        coachCareerService.slst(socket, socketData);

        String[] fields = socketData.getOutputData().get("VIEW0").split(",", -1);
        assertEquals(3, fields.length);
        assertEquals(":statbook/careerStatbook", fields[2]);
        assertEquals("1", socketData.getOutputData().get("COUNT"));
        verify(socketWriter).write(socket, socketData);
    }

    @Test
    void sviwAndSdtaHaveSameFieldCount() {
        SocketData sviwData = new SocketData("sviw", "VIEW=Career\0", null);
        coachCareerService.sviw(socket, sviwData);

        int count = Integer.parseInt(sviwData.getOutputData().get("N"));
        assertTrue(count > 0);
        for (String field : new String[]{"NAMES", "DESCS", "PARAMS", "TYPES"}) {
            assertEquals(count, sviwData.getOutputData().get(field).split(",", -1).length);
        }

        for (String pers : new String[]{"jack041", "Labeo"}) {
            SocketData sdtaData = new SocketData("sdta", "VIEW=Career\r\nSLOT=3\r\nPERS=" + pers + "\0", null);
            coachCareerService.sdta(socket, sdtaData);

            assertEquals("sdta", sdtaData.getIdMessage());
            assertEquals("3", sdtaData.getOutputData().get("SLOT"));
            assertEquals(count, sdtaData.getOutputData().get("STATS").split(",", -1).length);
            assertTrue(sdtaData.getOutputData().get("STATS").contains("Not tracked"));
            verify(socketWriter).write(socket, sdtaData);
        }
    }

    @Test
    void unknownViewOrMissingSlotReturnsError() {
        SocketData sviwData = new SocketData("sviw", "VIEW=Unknown\0", null);
        coachCareerService.sviw(socket, sviwData);
        assertEquals("sviwuviw", sviwData.getIdMessage());
        verify(socketWriter).write(socket, sviwData);

        SocketData sdtaData = new SocketData("sdta", "VIEW=Career\0", null);
        coachCareerService.sdta(socket, sdtaData);
        assertEquals("sdtaiper", sdtaData.getIdMessage());
        verify(socketWriter).write(socket, sdtaData);
    }
}
