package eu.eahub.services.stats;

import eu.eahub.config.TwConfig;
import eu.eahub.dirtysdk.TwStatsCodec.Field;
import eu.eahub.dirtysdk.TwTournamentCodec;
import eu.eahub.dto.TwStanding;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.entities.stats.TwEntryEntity;
import eu.eahub.entities.stats.TwPersonaStatsEntity;
import eu.eahub.repositories.core.PersonaRepository;
import eu.eahub.repositories.stats.TwEntryRepository;
import eu.eahub.repositories.stats.TwEventRepository;
import eu.eahub.repositories.stats.TwGolferRepository;
import eu.eahub.repositories.stats.TwPersonaStatsRepository;
import eu.eahub.services.TwTestConfig;
import eu.eahub.services.core.TwChallengeService;
import eu.eahub.steps.SocketWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static eu.eahub.services.server.GameServerService.PS2_TW04;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DataJpaTest
@ContextConfiguration(classes = TwTestConfig.class)
@Import({TwStatsService.class, TwTournamentService.class})
class TwTournamentServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    private static final String ALICE = "Alice";
    private static final String BOB = "Bob";
    private static final String CHARLIE = "Charlie";
    private static final String REFUSED_KEY = "$" + "00".repeat(16);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TwEventRepository twEventRepository;
    @Autowired
    private TwEntryRepository twEntryRepository;
    @Autowired
    private TwPersonaStatsRepository twPersonaStatsRepository;
    @Autowired
    private TwGolferRepository twGolferRepository;
    @Autowired
    private PersonaRepository personaRepository;
    @Autowired
    private TwStatsService twStatsService;
    @MockitoBean
    private TwChallengeService twChallengeService;
    @MockitoBean
    private SocketWriter socketWriter;
    @MockitoBean
    private TwConfig twConfig;
    private TwTournamentService tour;

    @BeforeEach
    void setup() {
        TwTestConfig.reset(jdbc, ALICE, BOB, CHARLIE);
        tour = tournament(CLOCK);
    }

    private static TwConfig config(Clock clock) {
        TwConfig config = mock(TwConfig.class);
        when(config.clock()).thenReturn(clock);
        return config;
    }

    private TwTournamentService tournament(Clock clock) {
        return new TwTournamentService(twEventRepository, twEntryRepository, twPersonaStatsRepository, twGolferRepository,
                mock(SocketWriter.class), config(clock));
    }

    private TwTournamentService on(String date) {
        return tournament(Clock.fixed(Instant.parse(date + "T12:00:00Z"), ZoneOffset.UTC));
    }

    private PersonaEntity persona(String name) {
        return personaRepository.findByPers(name).orElseThrow();
    }

    private TwPersonaStatsEntity career(TwTournamentService tournament, String name) {
        tournament.ensureCalendar(PS2_TW04);
        return twStatsService.getOrCreateStats(persona(name), PS2_TW04);
    }

    private static int day(String date) {
        return (int) ChronoUnit.DAYS.between(TwTournamentService.EPOCH, LocalDate.parse(date));
    }

    private static String card(String key, int day, int strokes) {
        int[] fields = {0, 1, 0, 1, 0, 5, strokes, 30, 18, 0, 2, 0, 9, 20, 14, 7, 321, 0, 16, 0, 0, 0, 1};
        ByteBuffer buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        for (int field : fields) {
            buffer.putInt(field);
        }
        buffer.put(HexFormat.of().parseHex(key.replace("$", "")));
        buffer.putShort((short) day);
        buffer.put((byte) 0);
        return HexFormat.of().formatHex(buffer.array());
    }

    private String play(TwTournamentService tournament, String name, int strokes) {
        Map<String, String> entry = tournament.start(PS2_TW04, persona(name));
        return tournament.receive(PS2_TW04, persona(name), card(entry.get("TKEY"), tournament.today(), strokes));
    }

    private static List<String> names(List<TwTournamentService.Round> board) {
        return board.stream().map(TwTournamentService.Round::getName).toList();
    }

    private List<TwStanding> board(TwTournamentService tournament, int index, Integer seasonFirst) {
        return tournament.board(PS2_TW04, index, seasonFirst, 0, 25, "");
    }

    @Test
    void listFormats() throws Exception {
        TwTournamentService.Event event = new TwTournamentService.Event(46314, "Test Open", 5, 2500000, 0x42008, null);
        String encoded = TwTournamentCodec.list(List.of(TwTournamentService.entry(event)));
        try (InputStream input = getClass().getResourceAsStream("/tw04-tournament-reference.txt")) {
            String[] lines = new String(input.readAllBytes(), StandardCharsets.US_ASCII).strip().split("\\R");
            assertEquals(lines[0], encoded);
            assertEquals(lines[1], TwTournamentCodec.list(List.of(Map.entry(ALICE, TwTournamentCodec.result(46314, 2, 70, 72)))));
        }
    }

    @Test
    void calendarAndRounds() {
        tour.ensureCalendar(PS2_TW04);
        int day = tour.today();
        TwTournamentService.Event event = tour.event(PS2_TW04, day).orElseThrow();
        long count = twEventRepository.count();
        assertEquals("00", tour.calendar(PS2_TW04, 65535, 100000));
        assertEquals(count, twEventRepository.count());
        assertTrue(tour.calendar(PS2_TW04, -1, 1).contains(event.getName()));
        assertTrue(twEventRepository.findAll().stream().noneMatch(stored -> stored.getCourse() == 22)); // never Tiger's Dream
        Map<String, String> entry = tour.start(PS2_TW04, persona(ALICE));
        String data = card(entry.get("TKEY"), day + 100, 70);
        assertTrue(tour.receive(PS2_TW04, persona(BOB), data).contains("not started"));
        tour = tournament(CLOCK);
        assertTrue(tour.receive(PS2_TW04, persona(ALICE), data).contains("recorded"));
        assertEquals(day, tour.day(PS2_TW04, day).getFirst().getDay());
        assertTrue(tour.day(PS2_TW04, day + 100).isEmpty());
        assertEquals(event.getCourse(), tour.day(PS2_TW04, day).getFirst().getCourse());
        assertTrue(tour.receive(PS2_TW04, persona(ALICE), card(entry.get("TKEY"), day, 50)).contains("already"));
        assertEquals(70, tour.day(PS2_TW04, day).getFirst().getStrokes());
        assertEquals("00", tour.results(PS2_TW04, persona(BOB), day, 1));
        assertTrue(tour.results(PS2_TW04, persona(ALICE), day, 1).contains(ALICE));
    }

    @Test
    void majors() {
        // 2026: THE PLAYERS Mar 12-15, Southern Apr 9-12, PGA May 14-17, US Jun 18-21, UK Jul 16-19
        String[][] expected = {
                {"2026-03-12", "2026-03-15", "THE PLAYERS Championship", "2", "12000000"},
                {"2026-04-09", "2026-04-12", "Southern Major Championship", "8", "15000000"},
                {"2026-05-14", "2026-05-17", "PGA TOUR Major Championship", "16", "15000000"},
                {"2026-06-18", "2026-06-21", "US Major Championship", "20", "15000000"},
                {"2026-07-16", "2026-07-19", "UK Major Championship", "15", "15000000"}
        };
        for (String[] major : expected) {
            int first = day(major[0]);
            assertEquals(first + 3, day(major[1]));
            assertNull(TwTournamentService.plan(first - 1).getSeries());
            assertNull(TwTournamentService.plan(first + 4).getSeries());
            for (int round = 0; round < 4; round++) {
                TwTournamentService.Event event = TwTournamentService.plan(first + round);
                assertEquals(major[2] + " R" + (round + 1), event.getName());
                assertEquals(first, event.getSeries());
                assertEquals(Integer.parseInt(major[3]), event.getCourse());
                assertEquals(Long.parseLong(major[4]), event.getPurse());
                assertEquals(TwTournamentCodec.MAJOR_ICON, TwTournamentService.entry(event).getValue()[15]);
            }
        }
        assertEquals(6, TwTournamentService.plan(day("2027-07-15")).getCourse());
    }

    @Test
    void majorIsPaidOnItsFinalStandings() {
        String[] days = {"2026-06-18", "2026-06-19", "2026-06-20", "2026-06-21"};
        int[][] strokes = {{70, 70, 70, 70}, {72, 72, 72, 72}, {66, 0, 66, 66}};
        String[] players = {ALICE, BOB, CHARLIE};
        String last = null;
        for (int round = 0; round < 4; round++) {
            TwTournamentService tournament = on(days[round]);
            tournament.ensureCalendar(PS2_TW04);
            for (int player = 0; player < 3; player++) {
                if (strokes[player][round] != 0) {
                    last = play(tournament, players[player], strokes[player][round]);
                }
            }
            if (round == 1) {
                List<TwTournamentService.Round> board = tournament.day(PS2_TW04, tournament.today());
                assertEquals(List.of(ALICE, BOB), names(board));
                assertEquals(140, board.getFirst().getTotal());
                assertEquals(2, board.getFirst().getRounds());
                assertEquals(0, career(tournament, ALICE).getEarnings()); // nothing is paid mid-event
            }
        }
        assertTrue(last.startsWith("Round 4 of 4: 66 is recorded."), last);
        assertTrue(last.contains("missed round"), last);
        TwTournamentService monday = on("2026-06-22");
        List<TwTournamentService.Round> finalStandings = monday.day(PS2_TW04, day("2026-06-21"));
        assertEquals(List.of(280, 288), finalStandings.stream().map(TwTournamentService.Round::getTotal).toList());
        TwPersonaStatsEntity alice = career(monday, ALICE);
        assertEquals(1, alice.getEvents());
        assertEquals(1, alice.getEventWins());
        assertEquals(TwTournamentService.payout(15_000_000, 1), alice.getEarnings());
        assertEquals(TwTournamentService.payout(15_000_000, 2), career(monday, BOB).getEarnings());
        TwPersonaStatsEntity charlie = career(monday, CHARLIE);
        assertEquals(1, charlie.getEvents());
        assertEquals(0, charlie.getEventWins());
        assertEquals(0, charlie.getEarnings());
        List<TwStanding> board = board(monday, 42, day("2026-06-21") - 6);
        assertEquals(ALICE, board.getFirst().getName());
        assertEquals(800 - 280, board.getFirst().getPoints());
        List<TwStanding> winners = board(monday, TwTournamentService.BOARD_LAST_WINNERS, 0);
        assertEquals(1, winners.size()); // only the final round names a winner
        assertEquals(ALICE, winners.getFirst().getName());
        // Settling again pays nothing twice
        tournament(Clock.offset(Clock.fixed(Instant.parse("2026-06-22T12:00:00Z"), ZoneOffset.UTC), Duration.ofDays(1))).ensureCalendar(PS2_TW04);
        assertEquals(TwTournamentService.payout(15_000_000, 1), career(monday, ALICE).getEarnings());
    }

    @Test
    void majorAllowsOneStartADay() {
        TwTournamentService tournament = on("2026-06-18");
        tournament.ensureCalendar(PS2_TW04);
        int day = tournament.today();
        Map<String, String> first = tournament.start(PS2_TW04, persona(ALICE));
        assertFalse(first.get("DATA").isEmpty());
        Map<String, String> again = tournament.start(PS2_TW04, persona(ALICE));
        assertEquals(REFUSED_KEY, again.get("TKEY"));
        assertEquals("", again.get("DATA"));
        String receipt = tournament.receive(PS2_TW04, persona(ALICE), card(first.get("TKEY"), day, 70));
        assertTrue(receipt.contains("That is your round for today"), receipt);
        assertEquals("", tournament.start(PS2_TW04, persona(ALICE)).get("DATA")); // still refused once played

        // Quitting the day's round is final; a later round (from before this rule) is ignored
        Map<String, String> bob = tournament.start(PS2_TW04, persona(BOB));
        String quit = card(bob.get("TKEY"), day, 40);
        quit = quit.substring(0, 16) + "01000000" + quit.substring(24);
        assertTrue(tournament.receive(PS2_TW04, persona(BOB), quit).contains("out of this event"));
        TwEntryEntity later = new TwEntryEntity();
        later.setToken("ff".repeat(16));
        later.setPersona(persona(BOB));
        later.setEvent(twEventRepository.findByVersAndEventDay(PS2_TW04, day).orElseThrow());
        later.setStartTime(LocalDateTime.of(2026, 6, 18, 12, 1)); // a minute after the first start
        twEntryRepository.save(later);
        tournament.receive(PS2_TW04, persona(BOB), card("ff".repeat(16), day, 60));
        assertEquals(List.of(ALICE), names(tournament.day(PS2_TW04, day)));

        // Single-day events still take every attempt
        TwTournamentService daily = on("2026-06-22");
        daily.ensureCalendar(PS2_TW04);
        daily.start(PS2_TW04, persona(ALICE));
        assertFalse(daily.start(PS2_TW04, persona(ALICE)).get("DATA").isEmpty());
    }

    @Test
    void weekendsAreThreeRoundEvents() {
        // 2026-10-23..25 is an ordinary weekend; the PLAYERS/major weekends keep four rounds
        int friday = day("2026-10-23");
        TwTournamentService.Event fridayEvent = TwTournamentService.plan(friday);
        assertEquals(friday, fridayEvent.getSeries());
        assertEquals(friday + 2, TwTournamentService.lastDay(fridayEvent));
        assertTrue(fridayEvent.getName().endsWith(" R1"), fridayEvent.getName());
        for (int offset = 1; offset < 3; offset++) {
            TwTournamentService.Event event = TwTournamentService.plan(friday + offset);
            assertEquals(fridayEvent.getSeries(), event.getSeries());
            assertEquals(fridayEvent.getCourse(), event.getCourse());
            assertEquals(fridayEvent.getConditions(), event.getConditions());
            assertEquals(fridayEvent.getPurse(), event.getPurse());
            assertTrue(event.getName().endsWith(" R" + (offset + 1)), event.getName());
        }
        assertNull(TwTournamentService.plan(friday - 1).getSeries()); // Thursday
        assertNull(TwTournamentService.plan(friday + 3).getSeries()); // Monday
        assertEquals(day("2026-06-18"), TwTournamentService.plan(day("2026-06-20")).getSeries());
        assertNotEquals(TwTournamentCodec.MAJOR_ICON, TwTournamentService.entry(fridayEvent).getValue()[15]); // no major star

        String[] days = {"2026-10-23", "2026-10-24", "2026-10-25"};
        int[][] strokes = {{70, 70, 70}, {68, 0, 68}};
        String[] players = {ALICE, BOB};
        String receipt = null;
        for (int round = 0; round < 3; round++) {
            TwTournamentService tournament = on(days[round]);
            tournament.ensureCalendar(PS2_TW04);
            for (int player = 0; player < 2; player++) {
                if (strokes[player][round] == 0) {
                    continue;
                }
                receipt = play(tournament, players[player], strokes[player][round]);
                if (round == 0 && player == 0) {
                    assertTrue(receipt.startsWith("Round 1 of 3: 70 is recorded."), receipt);
                    assertEquals("", tournament.start(PS2_TW04, persona(ALICE)).get("DATA"));
                }
            }
        }
        assertTrue(receipt.startsWith("Round 3 of 3: 68 is recorded."), receipt);
        TwTournamentService monday = on("2026-10-26");
        List<TwTournamentService.Round> standings = monday.day(PS2_TW04, day("2026-10-25"));
        assertEquals(List.of(ALICE), names(standings));
        assertEquals(210, standings.getFirst().getTotal());
        assertEquals(TwTournamentService.payout(fridayEvent.getPurse(), 1), career(monday, ALICE).getEarnings());
        assertEquals(1, career(monday, ALICE).getEvents());
    }

    @Test
    void bestReplayCountsAndEarningsFeedBoards() {
        int day = tour.today();
        Map<String, String> alice = tour.start(PS2_TW04, persona(ALICE));
        Map<String, String> bob = tour.start(PS2_TW04, persona(BOB));
        tour.receive(PS2_TW04, persona(ALICE), card(alice.get("TKEY"), day, 72));
        tour.receive(PS2_TW04, persona(BOB), card(bob.get("TKEY"), day, 70));
        Map<String, String> replay = tour.start(PS2_TW04, persona(ALICE));
        tour.receive(PS2_TW04, persona(ALICE), card(replay.get("TKEY"), day, 70));
        assertEquals(2, tour.day(PS2_TW04, day).size());
        assertEquals(1, tour.day(PS2_TW04, day).getLast().getRank());
        assertEquals(0, career(tour, ALICE).getEarnings());
        tour = tournament(Clock.offset(CLOCK, Duration.ofDays(1)));
        TwPersonaStatsEntity career = career(tour, ALICE);
        assertEquals(1, career.getEvents());
        assertEquals(1, career.getEventWins());
        assertTrue(career.getEarnings() > 0);
        assertEquals(1, tour.earningsRank(PS2_TW04, persona(ALICE)));
        assertEquals(career(tour, ALICE).getEarnings(), career(tour, BOB).getEarnings());
        assertEquals(2, board(tour, TwTournamentService.BOARD_LAST_WINNERS, tour.today() - 7).size());
        assertFalse(board(tour, TwTournamentService.BOARD_MONEY_LEADERS, tour.today() - 7).isEmpty());
        List<TwStanding> daily = board(tour, 42, tour.today() - 7);
        assertEquals(2, daily.size());
        assertEquals(130, daily.getFirst().getPoints());
        assertEquals(List.of(ALICE), tour.board(PS2_TW04, 42, null, 0, 25, "aLICE").stream().map(TwStanding::getName).toList());
        long[] values = twStatsService.standing(persona(ALICE), PS2_TW04).getValues();
        assertEquals(1, values[Field.EVENTS.index]);
        assertEquals(1, values[Field.EVENT_WINS.index]);
        assertEquals(career.getEarnings() / 100, values[Field.EARNINGS.index]);
        assertEquals(70, values[Field.BEST_ROUND.index]);
        assertEquals(71, values[Field.SCORING_AVERAGE.index]);
        assertEquals(4, values[Field.BIRDIES.index]); // both completed attempts contribute golf statistics
    }

    @Test
    void lateScorecardIsStillPaid() {
        int day = tour.today();
        Map<String, String> late = tour.start(PS2_TW04, persona(BOB));
        play(tour, ALICE, 72);
        TwTournamentService tomorrow = tournament(Clock.offset(CLOCK, Duration.ofDays(1)));
        long purse = tour.event(PS2_TW04, day).orElseThrow().getPurse();
        assertEquals(TwTournamentService.payout(purse, 1), career(tomorrow, ALICE).getEarnings());
        // Bob's round from yesterday arrives after the day was settled, within the entry's 48 hours, and wins
        assertTrue(tomorrow.receive(PS2_TW04, persona(BOB), card(late.get("TKEY"), day, 68)).contains("recorded"));
        assertEquals(TwTournamentService.payout(purse, 1), career(tomorrow, BOB).getEarnings());
        assertEquals(TwTournamentService.payout(purse, 2), career(tomorrow, ALICE).getEarnings());
        assertEquals(0, career(tomorrow, ALICE).getEventWins());
        assertEquals(1, career(tomorrow, BOB).getEventWins());
    }

    @Test
    void badScorecards() {
        int day = tour.today();
        Map<String, String> start = tour.start(PS2_TW04, persona(ALICE));
        String good = card(start.get("TKEY"), day, 70);
        assertTrue(tour.receive(PS2_TW04, persona(ALICE), "g".repeat(256)).contains("could not"));
        byte[] partial = HexFormat.of().parseHex(good);
        ByteBuffer.wrap(partial).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0);
        assertTrue(tour.receive(PS2_TW04, persona(ALICE), HexFormat.of().formatHex(partial)).contains("incomplete"));
        assertTrue(tour.day(PS2_TW04, day).isEmpty());
        start = tour.start(PS2_TW04, persona(ALICE));
        good = card(start.get("TKEY"), day, 70);
        tour = tournament(Clock.offset(CLOCK, Duration.ofDays(3)));
        assertTrue(tour.receive(PS2_TW04, persona(ALICE), good).contains("expired"));
        byte[] ace = HexFormat.of().parseHex(good);
        ByteBuffer.wrap(ace).order(ByteOrder.LITTLE_ENDIAN).putInt(44, 1).putInt(72, 15);
        assertEquals(1, TwTournamentCodec.cardFields(TwTournamentCodec.decodeCard(HexFormat.of().formatHex(ace))).get("ACES"));
    }

    @Test
    void golferUpload() {
        tour.saveGolfer(PS2_TW04, persona(ALICE), "$0102");
        tour.saveGolfer(PS2_TW04, persona(ALICE), "AB");
        assertEquals("$4142", twGolferRepository.findByPersonaIdAndVers(persona(ALICE).getId(), PS2_TW04).orElseThrow().getData());
        assertEquals(1, twGolferRepository.count());
    }
}
