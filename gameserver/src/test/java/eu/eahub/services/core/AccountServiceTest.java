package eu.eahub.services.core;

import eu.eahub.dto.SocketData;
import eu.eahub.entities.core.AccountEntity;
import eu.eahub.mappers.SocketMapper;
import eu.eahub.repositories.core.AccountRepository;
import eu.eahub.repositories.core.BlacklistRepository;
import eu.eahub.services.server.GameServerService;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.EmailUtils;
import eu.eahub.utils.PasswordUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.Socket;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final PasswordUtils passwordUtils = mock(PasswordUtils.class);
    private final SocketWriter socketWriter = mock(SocketWriter.class);
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        SocketMapper socketMapper = new SocketMapper();
        ReflectionTestUtils.setField(socketMapper, "passwordUtils", passwordUtils);
        accountService = new AccountService(passwordUtils, socketMapper, accountRepository, mock(BlacklistRepository.class),
                mock(PersonaService.class), mock(GameServerService.class), socketWriter, mock(EmailUtils.class));
        when(accountRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByMail(anyString())).thenReturn(List.of());
        when(passwordUtils.ssc2Decode(anyString())).thenReturn("secret");
        when(passwordUtils.bCryptEncode(anyString())).thenReturn("hash");
    }

    private AccountEntity create(String input) {
        accountService.acct(mock(Socket.class), new SocketData("acct", input, null));
        ArgumentCaptor<AccountEntity> captor = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createIgnoresClientSuppliedId() {
        AccountEntity account = create("ID=42\nNAME=player\nPASS=secret\nMAIL=player@example.com\n");

        assertNull(account.getId());
        assertEquals("player", account.getName());
    }

    @Test
    void createWithoutIdStillWorks() {
        AccountEntity account = create("NAME=player\nPASS=secret\nMAIL=player@example.com\n");

        assertNull(account.getId());
        assertEquals("player", account.getName());
        assertEquals("player@example.com", account.getMail());
        assertEquals("hash", account.getPass());
    }
}
