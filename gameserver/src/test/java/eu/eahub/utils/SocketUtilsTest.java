package eu.eahub.utils;

import org.junit.jupiter.api.Test;

import static eu.eahub.utils.SocketUtils.RETURN_CHAR;
import static eu.eahub.utils.SocketUtils.getCoachValueFromSocket;
import static eu.eahub.utils.SocketUtils.getValueFromSocket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SocketUtilsTest {

    private static final String USERPARAMS = "^\u0084\u0085Ô\u0080";
    private static final String DATA = "USERFLAGS=1\r\nUSERPARAMS=" + USERPARAMS + "\nPARAMS=a=b=c\nNAME=\0";

    @Test
    void getValueFromSocketKeepsGenericParsing() {
        assertEquals("\\R", RETURN_CHAR);
        assertEquals("1", getValueFromSocket(DATA, "USERFLAGS"));
        // \R splits on 0x85, '=' truncates the value and an empty value is null
        assertEquals("^\u0084", getValueFromSocket(DATA, "USERPARAMS"));
        assertEquals("a", getValueFromSocket(DATA, "PARAMS"));
        assertNull(getValueFromSocket(DATA, "NAME"));
        assertNull(getValueFromSocket(DATA, "SYSFLAGS"));
    }

    @Test
    void getCoachValueFromSocketKeepsBinaryAndEmbeddedEquals() {
        assertEquals("1", getCoachValueFromSocket(DATA, "USERFLAGS"));
        assertEquals(USERPARAMS, getCoachValueFromSocket(DATA, "USERPARAMS"));
        assertEquals("a=b=c", getCoachValueFromSocket(DATA, "PARAMS"));
    }

    @Test
    void getCoachValueFromSocketSeparatesEmptyFromMissing() {
        assertEquals("", getCoachValueFromSocket(DATA, "NAME"));
        assertNull(getCoachValueFromSocket(DATA, "SYSFLAGS"));
    }
}
