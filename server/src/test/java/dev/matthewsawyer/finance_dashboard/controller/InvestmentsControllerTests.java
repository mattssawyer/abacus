package dev.matthewsawyer.finance_dashboard.controller;

import dev.matthewsawyer.finance_dashboard.controller.InvestmentsController.HistoryResponse;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.AccountAdded;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.AccountDropped;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.AccountSeries;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.History;
import dev.matthewsawyer.finance_dashboard.history.BalanceHistory.Point;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvestmentsControllerTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T15:00:00Z"), ZoneOffset.UTC);

    @Mock private BalanceHistory balanceHistory;
    @Mock private UserService userService;

    private InvestmentsController controller;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        controller = new InvestmentsController(balanceHistory, userService, CLOCK);
        jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user_123").build();
    }

    @Test
    void returnsTheUsersHistoryThroughToday() {
        signedIn();
        LocalDate from = TODAY.minusDays(30);
        when(balanceHistory.forUser(USER_ID, from, TODAY)).thenReturn(new History(
                List.of(new Point(TODAY, new BigDecimal("45000"))),
                List.of(new AccountSeries("ira", List.of(new Point(TODAY, new BigDecimal("40000"))))),
                List.of(new AccountAdded(TODAY, "ira", "Roth IRA")),
                List.of(new AccountDropped(TODAY, "old", "Old brokerage")),
                List.of("euro-savings")));

        HistoryResponse response = controller.getHistory(jwt, from);

        assertEquals(TODAY, response.netWorth().get(0).date());
        assertEquals("ira", response.accounts().get(0).accountId());
        assertEquals(new BigDecimal("40000"), response.accounts().get(0).points().get(0).value());
        assertEquals("Roth IRA", response.accountsAdded().get(0).name());
        assertEquals("Old brokerage", response.accountsDropped().get(0).name());
        assertEquals(List.of("euro-savings"), response.leftOutOfNetWorth());
    }

    @Test
    void rejectsARangeStartingAfterToday() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.getHistory(jwt, TODAY.plusDays(1)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(balanceHistory);
    }

    private void signedIn() {
        User user = new User("user_123");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        when(userService.getOrCreateUser(jwt)).thenReturn(user);
    }
}
