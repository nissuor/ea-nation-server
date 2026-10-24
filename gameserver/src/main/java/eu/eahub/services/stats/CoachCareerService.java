package eu.eahub.services.stats;

import eu.eahub.dto.SocketData;
import eu.eahub.steps.SocketWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.Socket;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static eu.eahub.utils.SocketUtils.getValueFromSocket;

@Slf4j
@RequiredArgsConstructor
@Service
public class CoachCareerService {

    private static final String CAREER_VIEW = "Career";

    private final SocketWriter socketWriter;

    /**
     * slst - List the stats views (NFL Head Coach career screens)
     * The 3rd field of VIEW0 is the movie loaded by the career screen (':' is the screen root),
     * an empty or invalid one never loads the child screen, so the Back button stays disabled
     *
     * @param socket     The socket to write the response to
     * @param socketData The socket data
     */
    public void slst(Socket socket, SocketData socketData) {
        Map<String, String> content = Stream.of(new String[][]{
                {"COUNT", "1"},
                {"VIEW0", CAREER_VIEW + ",\"Local Career\",:statbook/careerStatbook"},
        }).collect(Collectors.toMap(data -> data[0], data -> data[1]));

        socketData.setOutputData(content);
        socketWriter.write(socket, socketData);
    }

    /**
     * sviw - Request names for each fields in a user's stats record
     * N must be positive (the game waits forever with N=0) and TYPES is required, even for plain text
     *
     * @param socket     The socket to write the response to
     * @param socketData The socket data
     */
    public void sviw(Socket socket, SocketData socketData) {
        String view = getValueFromSocket(socketData.getInputMessage(), "VIEW");
        if (!CAREER_VIEW.equals(view)) {
            socketData.setIdMessage("sviwuviw"); // Unknown view
        } else {
            Map<String, String> content = Stream.of(new String[][]{
                    {"N", "2"},
                    {"NAMES", "Records,History"},
                    {"DESCS", "\"Local career records\",\"Original EA history\""},
                    {"PARAMS", "0,0"},
                    {"TYPES", "0,0"},
            }).collect(Collectors.toMap(data -> data[0], data -> data[1]));

            socketData.setOutputData(content);
        }
        socketWriter.write(socket, socketData);
    }

    /**
     * sdta - Request the stats record of a user
     * SLOT must be sent back so the game can match the response with its request.
     * Career stats aren't stored yet, so placeholder values are returned.
     *
     * @param socket     The socket to write the response to
     * @param socketData The socket data
     */
    public void sdta(Socket socket, SocketData socketData) {
        String view = getValueFromSocket(socketData.getInputMessage(), "VIEW");
        String slot = getValueFromSocket(socketData.getInputMessage(), "SLOT");
        if (!CAREER_VIEW.equals(view) || slot == null || !slot.matches("\\d{1,4}")) {
            socketData.setIdMessage("sdtaiper"); // Invalid parameters
        } else {
            Map<String, String> content = Stream.of(new String[][]{
                    {"SLOT", slot},
                    {"STATS", "\"Not tracked\",\"Unavailable\""},
            }).collect(Collectors.toMap(data -> data[0], data -> data[1]));

            socketData.setOutputData(content);
        }
        socketWriter.write(socket, socketData);
    }

}
