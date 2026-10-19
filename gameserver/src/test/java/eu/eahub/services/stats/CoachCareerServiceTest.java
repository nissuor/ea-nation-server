package eu.eahub.services.stats;

import eu.eahub.dto.SocketData;
import eu.eahub.steps.SocketWriter;
import org.junit.jupiter.api.Test;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoachCareerServiceTest {
    private final SocketWriter writer = mock(SocketWriter.class);
    private final CoachCareerService service = new CoachCareerService(writer);
    private final Socket socket = mock(Socket.class);

    @Test void advertisedViewLoadsTheBuiltInCareerMovie() {
        SocketData packet = new SocketData("slst", "", null);
        service.slst(socket, packet);
        String[] fields = packet.getOutputData().get("VIEW0").split(",", -1);
        assertEquals(3, fields.length);
        assertEquals(":statbook/careerStatbook", fields[2]);
        assertEquals("1", packet.getOutputData().get("COUNT"));
        verify(writer).write(socket, packet);
    }

    @Test void schemaAndValuesHaveMatchingNonzeroFieldCountsAndEchoSlot() {
        SocketData view = new SocketData("sviw", "VIEW=Career\0", null);
        service.sviw(socket, view);
        int count = Integer.parseInt(view.getOutputData().get("N"));
        assertTrue(count > 0);
        for (String field : new String[]{"NAMES", "DESCS", "PARAMS", "TYPES"}) {
            assertEquals(count, view.getOutputData().get(field).split(",", -1).length);
        }
        for (String persona : new String[]{"jack041", "Labeo"}) {
            SocketData data = new SocketData("sdta", "VIEW=Career\r\nSLOT=3\r\nPERS=" + persona + "\0", null);
            service.sdta(socket, data);
            assertEquals("sdta", data.getIdMessage());
            assertEquals("3", data.getOutputData().get("SLOT"));
            assertEquals(count, data.getOutputData().get("STATS").split(",", -1).length);
            assertTrue(data.getOutputData().get("STATS").contains("Not tracked"));
            verify(writer).write(socket, data);
        }
    }

    @Test void unknownViewAndMissingSlotFinishWithProtocolErrors() {
        SocketData view = new SocketData("sviw", "VIEW=Unknown\0", null);
        service.sviw(socket, view);
        assertEquals("sviwuviw", view.getIdMessage());
        SocketData data = new SocketData("sdta", "VIEW=Career\0", null);
        service.sdta(socket, data);
        assertEquals("sdtaiper", data.getIdMessage());
        verify(writer).write(socket, view);
        verify(writer).write(socket, data);
    }
}
