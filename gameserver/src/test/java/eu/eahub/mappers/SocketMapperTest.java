package eu.eahub.mappers;

import eu.eahub.entities.core.AccountEntity;
import eu.eahub.utils.PasswordUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocketMapperTest {

    private final SocketMapper socketMapper = new SocketMapper();

    @BeforeEach
    void setUp() {
        PasswordUtils passwordUtils = mock(PasswordUtils.class);
        ReflectionTestUtils.setField(socketMapper, "passwordUtils", passwordUtils);
        when(passwordUtils.ssc2Decode(anyString())).thenReturn("secret");
        when(passwordUtils.bCryptEncode(anyString())).thenReturn("hash");
    }

    @Test
    void accountIgnoresClientId() {
        AccountEntity account = socketMapper.toAccountEntity("ID=42\nNAME=player\nPASS=secret\nMAIL=player@example.com\n");

        assertNull(account.getId());
        assertEquals("player", account.getName());
    }

    @Test
    void accountWithoutId() {
        AccountEntity account = socketMapper.toAccountEntity("NAME=player\nPASS=secret\nMAIL=player@example.com\n");

        assertNull(account.getId());
        assertEquals("player", account.getName());
        assertEquals("player@example.com", account.getMail());
        assertEquals("hash", account.getPass());
    }
}
