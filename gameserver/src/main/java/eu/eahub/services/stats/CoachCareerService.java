package eu.eahub.services.stats;

import eu.eahub.dto.SocketData;
import eu.eahub.steps.SocketWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.net.Socket;
import java.util.Map;
import static eu.eahub.utils.SocketUtils.getValueFromSocket;

/** Minimal, honest career view for the local PC Coach server. */
@Service
@RequiredArgsConstructor
public class CoachCareerService {
    private final SocketWriter socketWriter;

    public void slst(Socket socket, SocketData packet) {
        // CScreen_career loads strStyle as a movie. ':' expands SCREEN.ROOT;
        // the old empty style never loaded a child or re-enabled Back.
        packet.setOutputData(Map.of("COUNT", "1",
                "VIEW0", "Career,\"Local Career\",:statbook/careerStatbook"));
        socketWriter.write(socket, packet);
    }

    public void sviw(Socket socket, SocketData packet) {
        if (!"Career".equals(getValueFromSocket(packet.getInputMessage(), "VIEW"))) {
            packet.setIdMessage("sviwuviw");
        } else {
            // N must be positive: the client's asynchronous loader waits at N=0.
            // TYPES is required even for plain text. PARAMS are statbook metadata.
            packet.setOutputData(Map.of("N", "2", "NAMES", "Records,History",
                    "DESCS", "\"Local career records\",\"Original EA history\"",
                    "PARAMS", "0,0", "TYPES", "0,0"));
        }
        socketWriter.write(socket, packet);
    }

    public void sdta(Socket socket, SocketData packet) {
        String slot = getValueFromSocket(packet.getInputMessage(), "SLOT");
        if (!"Career".equals(getValueFromSocket(packet.getInputMessage(), "VIEW"))
                || slot == null || !slot.matches("[0-9]{1,4}")) {
            packet.setIdMessage("sdtaiper");
        } else {
            // Echo SLOT so the matching pending operation can complete. Do not
            // fabricate wins/losses: match-result persistence is not implemented.
            packet.setOutputData(Map.of("SLOT", slot,
                    "STATS", "\"Not tracked\",\"Unavailable\""));
        }
        socketWriter.write(socket, packet);
    }
}
