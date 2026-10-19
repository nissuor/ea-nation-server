package eu.eahub.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SocketUtilsTest {
    @Test void preservesBinarySettingsAndEmbeddedEquals() {
        String settings = "^\u0084\u0085\u00d4\u0080";
        String packet = "USERFLAGS=1\r\nUSERPARAMS=" + settings + "\nPARAMS=a=b=c\0";
        assertEquals(settings, SocketUtils.getValueFromSocket(packet, "USERPARAMS"));
        assertEquals("a=b=c", SocketUtils.getValueFromSocket(packet, "PARAMS"));
        assertEquals("1", SocketUtils.getValueFromSocket(packet, "USERFLAGS"));
    }
    @Test void distinguishesClearedFieldsFromMissingFields() {
        assertEquals("", SocketUtils.getValueFromSocket("PARAMS=\0", "PARAMS"));
        assertNull(SocketUtils.getValueFromSocket("USERFLAGS=1\0", "PARAMS"));
    }
}
