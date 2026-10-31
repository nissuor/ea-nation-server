package eu.eahub.services.stats;

import eu.eahub.config.TwConfig;
import eu.eahub.dirtysdk.LobbyTagField;
import eu.eahub.dirtysdk.TwTournamentCodec;
import eu.eahub.dto.SocketData;
import eu.eahub.dto.SocketWrapper;
import eu.eahub.dto.TwStanding;
import eu.eahub.entities.core.PersonaEntity;
import eu.eahub.entities.stats.TwEntryEntity;
import eu.eahub.entities.stats.TwEventEntity;
import eu.eahub.entities.stats.TwGolferEntity;
import eu.eahub.entities.stats.TwPersonaStatsEntity;
import eu.eahub.repositories.stats.TwEntryRepository;
import eu.eahub.repositories.stats.TwEventRepository;
import eu.eahub.repositories.stats.TwGolferRepository;
import eu.eahub.repositories.stats.TwPersonaStatsRepository;
import eu.eahub.steps.SocketWriter;
import eu.eahub.utils.TwCardUtils;
import jakarta.transaction.Transactional;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiger Woods tournaments: the server-owned calendar, start keys, scorecards and boards
 * Days count from 1899-12-30 like the game, and change at midnight in tw04.timezone
 * Wire formats are OnlineTourn.c's, see THIRD-PARTY-TW04.txt
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TwTournamentService {

    public static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    // The boards the STATISTICS screen asks for (snap INDEX): the season's days, the weeks' money, then two text lists
    public static final int BOARD_SEASON_FIRST = 36;
    public static final int BOARD_SEASON_LAST = 50;
    public static final int BOARD_WEEK_MONEY_FIRST = 52;
    public static final int BOARD_WEEK_MONEY_LAST = 54;
    public static final int BOARD_LAST_WINNERS = 33;
    public static final int BOARD_MONEY_LEADERS = 35;
    // The season shown on the STATISTICS screen starts a week ago
    private static final int SEASON_DAYS_BEFORE_TODAY = 7;
    private static final int SEASON_DAYS = 15;
    static final int MAJOR_DAYS = 4;
    static final int WEEKEND_DAYS = 3;
    private static final int MAX_DAY = 65535;
    private static final int MAX_CALENDAR_DAYS = 32;
    private static final int MAX_GOLFER_LENGTH = 8193;
    private static final int EVENT_NAME_LENGTH = 32;
    private static final int ROUND_NAME_LENGTH = 29; // Leaves room for " R1"
    private static final long ENTRY_LIFETIME_HOURS = 48;
    private static final long PAR_CACHE_NANOS = Duration.ofSeconds(60).toNanos();
    private static final long PURSE_STEP = 50000;
    private static final long DAILY_PURSE = 5000000;
    private static final long DAILY_PURSE_PER_DIFFICULTY = 150000;
    private static final double WEEKEND_PURSE_FACTOR = 1.5;
    // The game's own day numbering of its lists starts in 2003
    private static final int LIST_EPOCH_DAY = (int) ChronoUnit.DAYS.between(EPOCH, LocalDate.of(2003, 1, 1));
    private static final int MAX_LIST_DAY = 32766;
    // The highest score a board row can sort by: lower scores rank first
    private static final long ROUND_SORT_BASE = 200;
    private static final long MONEY_SORT_UNIT = 10000;
    // RNKRS is 36 little-endian words; MY RESUME's earnings rank is word 10 (0x002A0E08)
    private static final int RESUME_LENGTH = 144;
    private static final int RESUME_EARNINGS_RANK_OFFSET = 40;
    // Courses hardest first. Excludes 7 Skillz, 21 NA and every composite (22..29): their hole lists live in each
    // player's profile (SLUS-20757 0x33FEE0, slot via 0x1BA2B0; built at 0x225830), so an unfilled slot plays Pebble Beach
    private static final int[] DIFFICULTY = {17, 9, 18, 12, 4, 3, 2, 5, 8, 1, 16, 14, 6, 15, 11, 10, 19, 0, 20, 13};
    private static final String[] DAILY_SUFFIXES = {"Open", "Classic", "Invitational", "Championship", "Masters", "Challenge", "Cup"};
    private static final long DAILY_SEED = 0x54573034L;
    // Each day's conditions pick one of three values per bit group, with its purse factor
    private static final int[][] CONDITION_BITS = {{0, 1 << 4, 1 << 3}, {0, 1 << 13, 1 << 15}, {0, 1 << 17, 1 << 18}, {0, 1 << 10, 1 << 12}};
    private static final double[][] CONDITION_FACTORS = {{1.10, 1, .90}, {1, .95, 1.10}, {.97, 1, 1.05}, {1, .95, 1.10}};
    private static final double[] PAYOUT_RATES = {.18, .109, .068, .048, .04, .036, .0335, .031, .029, .027};
    private static final double PAYOUT_DECAY = .85;
    // TW04's own season names (LOADONCE.PS2 PGAn). THE PLAYERS is not a major but fits the pools
    private static final List<Major> MAJORS = List.of(
            new Major("THE PLAYERS Championship", 3, 3, 12_000_000, 1 << 12, new int[]{2}),
            new Major("Southern Major Championship", 4, 2, 15_000_000, 1 << 12, new int[]{20, 8, 16}),
            new Major("PGA TOUR Major Championship", 5, 3, 15_000_000, 1 << 15, new int[]{5, 16, 20}),
            new Major("US Major Championship", 6, 3, 15_000_000, 1 << 15 | 1 << 12, new int[]{0, 5, 20, 14}),
            new Major("UK Major Championship", 7, 3, 15_000_000, 1 << 18, new int[]{15, 6}));
    // esr2t without a key or an event: the client declines to start (0x002DE1FC)
    private static final Map<String, String> START_REFUSED = Map.of("CMD", "esr2t", "TKEY", "$" + "00".repeat(16), "DATA", "");

    private final TwEventRepository twEventRepository;
    private final TwEntryRepository twEntryRepository;
    private final TwPersonaStatsRepository twPersonaStatsRepository;
    private final TwGolferRepository twGolferRepository;
    private final SocketWriter socketWriter;
    private final TwConfig twConfig;
    private final Map<String, Integer> generatedDay = new ConcurrentHashMap<>();
    private final Map<String, Integer> settledDay = new ConcurrentHashMap<>();
    private final Map<String, CachedPar> pars = new ConcurrentHashMap<>();

    /**
     * A four-day event, Thursday to the given Sunday of its month, its course rotating through a pool by year
     */
    @Getter
    @AllArgsConstructor
    private static class Major {
        private final String name;
        private final int month;
        private final int sunday;
        private final long purse;
        private final int conditions;
        private final int[] pool;
    }

    /**
     * A calendar event. series is the first day of a multi-day event, or null for a single-day event
     */
    @Getter
    @AllArgsConstructor
    static class Event {
        private final int day;
        private final String name;
        private final int course;
        private final long purse;
        private final int conditions;
        private final Integer series;
    }

    /**
     * One player's place on a day's board. For a multi-day event the board is the running total:
     * total covers rounds days, one round per day, and fields/strokes are that day's round
     */
    @Getter
    @Setter
    @AllArgsConstructor
    static class Round {
        private final long personaId;
        private final String name;
        private final int day;
        private final int course;
        private final Map<String, Long> fields;
        private int rank;
        private final long purse;
        private final Integer series;
        private int total;
        private int rounds;

        int getStrokes() {
            return fields.get("STROKES").intValue();
        }
    }

    /**
     * A course's estimated par, and when it was estimated
     */
    @Getter
    @AllArgsConstructor
    private static class CachedPar {
        private final int value;
        private final long at;
    }

    /**
     * Handle a tournament or profile command (cusr CMD=...)
     * Only lts5d (today's date) is answered before a persona is selected
     *
     * @param socket        The socket to write the response to
     * @param socketData    The socket data
     * @param socketWrapper The socket wrapper of current connection
     */
    @Transactional
    public void cusr(Socket socket, SocketData socketData, SocketWrapper socketWrapper) {
        Map<String, String> fields = LobbyTagField.decode(socketData.getInputMessage());
        String cmd = fields.getOrDefault("CMD", "");
        PersonaEntity persona = socketWrapper.getPersonaEntity();
        if (persona == null && !cmd.equals("lts5d")) {
            socketWriter.writeText(socket, "cusrnusr", "");
            return;
        }
        String vers = persona == null ? null : socketWrapper.getPersonaConnectionEntity().getVers();
        switch (cmd) {
            case "lts5d" -> socketWriter.writeText(socket, "cusr", String.valueOf(today()));
            case "ufpvt" -> {
                ensureCalendar(vers);
                int seasonFirst = today() - SEASON_DAYS_BEFORE_TODAY;
                socketWrapper.setSeasonFirst(seasonFirst);
                socketWriter.writeText(socket, "cusr", seasonFirst + " " + (seasonFirst + SEASON_DAYS - 1) + " " + BOARD_SEASON_FIRST + " "
                        + BOARD_WEEK_MONEY_FIRST + " 64 0");
            }
            case "mg5ri" -> socketWriter.writeText(socket, "cusr", calendar(vers, LobbyTagField.getNumber(fields, "START", 0),
                    LobbyTagField.getNumber(fields, "NUM", 0)));
            case "qdb@w" -> socketWriter.writeText(socket, "cusr", results(vers, persona, LobbyTagField.getNumber(fields, "START", 0),
                    LobbyTagField.getNumber(fields, "NUM", 0)));
            case "esr2t" -> socketWriter.writeTagFields(socket, "cusr", start(vers, persona));
            case "5d0tr" -> socketWriter.writeText(socket, "cusr", receive(vers, persona, fields.getOrDefault("DATA", "")));
            case "myrnk" -> {
                byte[] resume = new byte[RESUME_LENGTH];
                ByteBuffer.wrap(resume).order(ByteOrder.LITTLE_ENDIAN).putInt(RESUME_EARNINGS_RANK_OFFSET, earningsRank(vers, persona));
                socketWriter.writeTagFields(socket, "cusr", Map.of("CMD", cmd, "RNKRS", "$" + HexFormat.of().formatHex(resume)));
            }
            case "whomi" -> {
                String golfer = fields.get("CRPIN");
                if (golfer != null && golfer.length() <= MAX_GOLFER_LENGTH) {
                    saveGolfer(vers, persona, golfer);
                }
                socketWriter.writeTagFields(socket, "cusr", Map.of("CMD", cmd));
            }
            default -> socketWriter.writeText(socket, "cusrunav", "");
        }
    }

    /**
     * Store a player's created golfer, uploaded with whomi
     * The upload is high-bit escaped text, while user replies expect $hex binary
     *
     * @param vers    The game version
     * @param persona The player
     * @param golfer  The golfer as uploaded
     */
    @Transactional
    public void saveGolfer(String vers, PersonaEntity persona, String golfer) {
        String data = golfer.startsWith("$") && golfer.substring(1).matches("(?:[0-9a-fA-F]{2})*")
                ? golfer
                : "$" + HexFormat.of().formatHex(golfer.getBytes(StandardCharsets.ISO_8859_1));
        TwGolferEntity entity = twGolferRepository.findByPersonaIdAndVers(persona.getId(), vers).orElseGet(() -> {
            TwGolferEntity created = new TwGolferEntity();
            created.setPersona(persona);
            created.setVers(vers);
            return created;
        });
        entity.setData(data);
        entity.setUpdatedOn(LocalDateTime.now());
        twGolferRepository.save(entity);
    }

    /**
     * Get today's day number
     *
     * @return The number of days since the game's epoch, in the configured timezone
     */
    public int today() {
        return (int) ChronoUnit.DAYS.between(EPOCH, LocalDate.now(twConfig.clock()));
    }

    /**
     * Get the current time
     * Stored timestamps are server time, like the rest of the database; the clock only decides the tournament day
     *
     * @return The current server time
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(twConfig.clock().instant(), ZoneId.systemDefault());
    }

    /**
     * Get the day whose standings are final for an event
     *
     * @param event The event
     * @return Its day for a single-day event, otherwise the last day of its series
     */
    static int lastDay(Event event) {
        return event.getSeries() == null ? event.getDay() : event.getSeries() + seriesDays(event.getSeries()) - 1;
    }

    /**
     * Get the length of the multi-day event that starts on a day
     * Majors and THE PLAYERS start on a Thursday; the weekend events in every other week start on a Friday
     *
     * @param start The event's first day
     * @return The number of rounds
     */
    static int seriesDays(int start) {
        return EPOCH.plusDays(start).getDayOfWeek() == DayOfWeek.THURSDAY ? MAJOR_DAYS : WEEKEND_DAYS;
    }

    /**
     * Store the calendar from last month to next month, then settle the days that have ended
     * Synchronized so that one day is generated once; a second caller that gets in between the generation and its
     * commit would fail on the event's unique constraint
     *
     * @param vers The game version
     */
    @Transactional
    public synchronized void ensureCalendar(String vers) {
        int day = today();
        if (!Objects.equals(generatedDay.get(vers), day)) {
            LocalDate month = LocalDate.now(twConfig.clock()).withDayOfMonth(1);
            for (LocalDate date = month.minusMonths(1); date.isBefore(month.plusMonths(2)); date = date.plusDays(1)) {
                int eventDay = (int) ChronoUnit.DAYS.between(EPOCH, date);
                if (eventDay < 0 || eventDay > MAX_DAY || twEventRepository.findByVersAndEventDay(vers, eventDay).isPresent()) {
                    continue;
                }
                Event planned = plan(eventDay);
                TwEventEntity event = new TwEventEntity();
                event.setVers(vers);
                event.setEventDay(eventDay);
                event.setName(planned.getName());
                event.setCourse(planned.getCourse());
                event.setPurse(planned.getPurse());
                event.setConditions(planned.getConditions());
                event.setSeriesStart(planned.getSeries());
                twEventRepository.save(event);
            }
            generatedDay.put(vers, day);
        }
        if (!Objects.equals(settledDay.get(vers), day)) {
            settle(vers, day);
            settledDay.put(vers, day);
        }
    }

    /**
     * Plan a day's event. The calendar is reproducible and owned by the server: a client's START never creates events
     *
     * @param day The day
     * @return The day's event
     */
    static Event plan(int day) {
        LocalDate date = EPOCH.plusDays(day);
        for (Major major : MAJORS) {
            LocalDate sunday = date.withMonth(major.getMonth()).with(TemporalAdjusters.dayOfWeekInMonth(major.getSunday(), DayOfWeek.SUNDAY));
            LocalDate thursday = sunday.minusDays(MAJOR_DAYS - 1);
            if (date.isBefore(thursday) || date.isAfter(sunday)) {
                continue;
            }
            int round = (int) ChronoUnit.DAYS.between(thursday, date) + 1;
            int course = major.getPool()[Math.floorMod(date.getYear(), major.getPool().length)];
            return new Event(day, major.getName() + " R" + round, course, major.getPurse(), major.getConditions(),
                    (int) ChronoUnit.DAYS.between(EPOCH, thursday));
        }
        if (date.getDayOfWeek().compareTo(DayOfWeek.FRIDAY) >= 0) {
            // Any other weekend is a three-round event on Friday's course and conditions
            LocalDate friday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
            int first = (int) ChronoUnit.DAYS.between(EPOCH, friday);
            Event base = daily(first);
            String name = base.getName().substring(0, Math.min(ROUND_NAME_LENGTH, base.getName().length())).strip();
            long purse = Math.round(base.getPurse() * WEEKEND_PURSE_FACTOR / PURSE_STEP) * PURSE_STEP;
            return new Event(day, name + " R" + (day - first + 1), base.getCourse(), purse, base.getConditions(), first);
        }
        return daily(day);
    }

    /**
     * Plan a single-day event: course by rotation, reproducible random conditions and purse
     *
     * @param day The day
     * @return The day's event
     */
    private static Event daily(int day) {
        int difficulty = Math.floorMod(day, DIFFICULTY.length);
        int course = DIFFICULTY[difficulty];
        Random random = new Random(DAILY_SEED + day);
        int conditions = 0;
        double multiplier = 1;
        for (int group = 0; group < CONDITION_BITS.length; group++) {
            int choice = random.nextInt(4);
            choice = choice < 2 ? 0 : choice - 1;
            conditions |= CONDITION_BITS[group][choice];
            multiplier *= CONDITION_FACTORS[group][choice];
        }
        long purse = Math.round((DAILY_PURSE - difficulty * DAILY_PURSE_PER_DIFFICULTY) * multiplier / PURSE_STEP) * PURSE_STEP;
        String name = TwCardUtils.COURSES[course] + " " + DAILY_SUFFIXES[Math.floorMod(day, DAILY_SUFFIXES.length)];
        if (name.length() > EVENT_NAME_LENGTH) {
            name = name.substring(0, EVENT_NAME_LENGTH);
        }
        return new Event(day, name, course, purse, conditions, null);
    }

    /**
     * Get a day's stored event
     *
     * @param vers The game version
     * @param day  The day
     * @return The event, if the calendar holds that day
     */
    Optional<Event> event(String vers, int day) {
        return twEventRepository.findByVersAndEventDay(vers, day).map(TwTournamentService::event);
    }

    /**
     * Get a stored event as a calendar event
     *
     * @param entity The stored event
     * @return The calendar event
     */
    private static Event event(TwEventEntity entity) {
        return new Event(entity.getEventDay(), entity.getName(), entity.getCourse(), entity.getPurse(), entity.getConditions(),
                entity.getSeriesStart());
    }

    /**
     * Encode an event as a calendar entry
     *
     * @param event The event
     * @return The entry's name and data
     */
    static Map.Entry<String, byte[]> entry(Event event) {
        LocalDate date = EPOCH.plusDays(event.getDay());
        boolean major = event.getSeries() != null && seriesDays(event.getSeries()) == MAJOR_DAYS;
        int icon = TwTournamentCodec.icon(date.getMonthValue(), date.getDayOfMonth(), major);
        return Map.entry(event.getName(), TwTournamentCodec.event(event.getDay(), event.getCourse(), event.getPurse(), event.getConditions(), icon));
    }

    /**
     * Get the calendar (mg5ri)
     *
     * @param vers  The game version
     * @param start The first day, or -1 for today
     * @param count The number of days, at most 32
     * @return The events, as a list
     */
    public String calendar(String vers, int start, int count) {
        ensureCalendar(vers);
        if (start < 0) {
            start = today();
        }
        count = Math.clamp(count, 0, MAX_CALENDAR_DAYS);
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        if (start > MAX_DAY) {
            return TwTournamentCodec.list(entries);
        }
        for (TwEventEntity event : twEventRepository.findByVersAndEventDayBetweenOrderByEventDay(vers, start, Math.min(MAX_DAY, start + count - 1))) {
            entries.add(entry(event(event)));
        }
        return TwTournamentCodec.list(entries);
    }

    /**
     * Issue a start key for today's event (esr2t)
     * A multi-day event allows one round a day, the first one started
     *
     * @param vers    The game version
     * @param persona The player
     * @return The key and the event, or a refusal
     */
    @Transactional
    public Map<String, String> start(String vers, PersonaEntity persona) {
        ensureCalendar(vers);
        Optional<TwEventEntity> found = twEventRepository.findByVersAndEventDay(vers, today());
        if (found.isEmpty()) {
            return START_REFUSED;
        }
        TwEventEntity event = found.get();
        if (event.getSeriesStart() != null && twEntryRepository.existsByPersonaIdAndEventId(persona.getId(), event.getId())) {
            return START_REFUSED;
        }
        String key = UUID.randomUUID().toString().replace("-", "");
        TwEntryEntity entry = new TwEntryEntity();
        entry.setToken(key);
        entry.setEvent(event);
        entry.setPersona(persona);
        entry.setStartTime(now());
        twEntryRepository.save(entry);
        return Map.of("CMD", "esr2t", "TKEY", "$" + key, "DATA", TwTournamentCodec.list(List.of(entry(event(event)))));
    }

    /**
     * Record a scorecard against the start key it carries (5d0tr)
     *
     * @param vers    The game version
     * @param persona The player
     * @param data    The card, in hex
     * @return The receipt shown to the player
     */
    @Transactional
    public String receive(String vers, PersonaEntity persona, String data) {
        byte[] card;
        try {
            card = TwTournamentCodec.decodeCard(data);
        } catch (IllegalArgumentException e) {
            return "That scorecard could not be read or does not add up.";
        }
        Map<String, Long> fields = TwTournamentCodec.cardFields(card);
        Optional<TwEntryEntity> found = twEntryRepository.findForUpdateByTokenAndPersonaId(TwTournamentCodec.cardKey(card), persona.getId());
        if (found.isEmpty() || !vers.equals(found.get().getEvent().getVers())) {
            return "This round was not started on this server.";
        }
        TwEntryEntity entry = found.get();
        TwEventEntity event = entry.getEvent();
        int day = event.getEventDay();
        if (entry.getEndTime() != null) {
            return "This round has already been recorded.";
        }
        LocalDateTime now = now();
        if (Duration.between(entry.getStartTime(), now).toHours() >= ENTRY_LIFETIME_HOURS) {
            return "This tournament entry has expired.";
        }
        Integer series = event.getSeriesStart();
        boolean finished = finished(fields);
        boolean counted = finished && (series == null || firstStart(entry));
        int first = series == null ? day : series;
        int last = series == null ? day : series + seriesDays(series) - 1;
        if (counted) {
            // Golf counts in the player's statistics, and the event as entered once
            TwPersonaStatsEntity stats = stats(persona, vers);
            if (!twEntryRepository.existsByPersonaIdAndCountedTrueAndEventVersAndEventEventDayBetween(persona.getId(), vers, first, last)) {
                stats.setEvents(stats.getEvents() + 1);
            }
            TwStatsService.addGolf(stats, fields);
            twPersonaStatsRepository.save(stats);
        }
        TwCardUtils.setCard(entry, fields);
        entry.setEndTime(now);
        entry.setCounted(counted);
        twEntryRepository.save(entry);
        // A card for an event that has already been settled changes its final standings
        twEventRepository.findByVersAndEventDay(vers, last)
                .filter(lastDay -> lastDay.getSettledOn() != null && last < today())
                .ifPresent(lastDay -> settle(vers, lastDay));
        if (!finished) {
            if (series != null) {
                return "Round " + (day - series + 1) + " was not completed, so you are out of this event. Each day's first round is the only one that counts.";
            }
            return "Your incomplete round was saved. Only completed 18-hole rounds qualify.";
        }
        Optional<Round> standing = day(vers, day).stream().filter(round -> round.getPersonaId() == persona.getId()).findFirst();
        if (series != null) {
            int round = day - series + 1;
            String recorded = "Round " + round + " of " + seriesDays(series) + ": " + fields.get("STROKES") + " is recorded."
                    + (round < seriesDays(series) ? " That is your round for today; the next opens tomorrow." : "");
            if (standing.isEmpty()) {
                return recorded + "\nA missed round keeps you off the leaderboard.";
            }
            Round best = standing.get();
            return recorded + "\nTotal " + best.getTotal() + " (" + toPar(vers, best) + "). Place: " + best.getRank() + ".";
        }
        Round best = standing.orElseThrow();
        return "Your round of " + fields.get("STROKES") + " is recorded.\nBest today: " + best.getStrokes() + ". Place: " + best.getRank() + ".";
    }

    /**
     * Get a player's statistics, created if they have none yet
     *
     * @param persona The player
     * @param vers    The game version
     * @return The statistics
     */
    private TwPersonaStatsEntity stats(PersonaEntity persona, String vers) {
        return twPersonaStatsRepository.findByPersonaIdAndVers(persona.getId(), vers).orElseGet(() -> TwStatsService.newStats(persona, vers));
    }

    /**
     * Whether an entry is its player's first start of the day (the only round that counts in a multi-day event)
     *
     * @param entry The entry
     * @return True if no earlier entry of the player exists for the event
     */
    private boolean firstStart(TwEntryEntity entry) {
        return twEntryRepository.findByPersonaIdAndEventIdOrderByStartTimeAscIdAsc(entry.getPersona().getId(), entry.getEvent().getId())
                .getFirst().getId().equals(entry.getId());
    }

    /**
     * Whether a scorecard is a completed 18-hole round
     *
     * @param fields The scorecard fields
     * @return True for a finished round that was not quit
     */
    private static boolean finished(Map<String, Long> fields) {
        return fields.get("DONE") == 1 && fields.get("QUIT") == 0 && fields.get("HOLES") == 18;
    }

    /**
     * Get a stored entry as a board row, before it is ranked
     *
     * @param entry The entry
     * @return The row, its rank not set yet
     */
    private static Round round(TwEntryEntity entry) {
        Map<String, Long> fields = TwCardUtils.card(entry);
        return new Round(entry.getPersona().getId(), entry.getPersona().getPers(), entry.getEvent().getEventDay(), entry.getEvent().getCourse(),
                fields, 0, entry.getEvent().getPurse(), entry.getEvent().getSeriesStart(), fields.get("STROKES").intValue(), 1);
    }

    /**
     * Get a day's board: each player's best completed round that day. On a multi-day event it is the running total of
     * each day's one counted (first) round since the first day, and only players who have completed every day so far
     * are on it
     *
     * @param vers The game version
     * @param day  The day
     * @return The board, best first
     */
    List<Round> day(String vers, int day) {
        Optional<TwEventEntity> event = twEventRepository.findByVersAndEventDay(vers, day);
        if (event.isEmpty()) {
            return List.of();
        }
        Integer series = event.get().getSeriesStart();
        int first = series == null ? day : series;
        Map<Long, Map<Integer, Round>> best = new HashMap<>();
        for (TwEntryEntity entry : twEntryRepository.findCounted(vers, first, day)) {
            Round round = round(entry);
            if (Objects.equals(round.getSeries(), series)) {
                best.computeIfAbsent(round.getPersonaId(), personaId -> new HashMap<>())
                        .merge(round.getDay(), round, (one, other) -> one.getStrokes() <= other.getStrokes() ? one : other);
            }
        }
        int played = day - first + 1;
        List<Round> totals = new ArrayList<>();
        for (Map<Integer, Round> days : best.values()) {
            if (days.size() != played) {
                continue;
            }
            Round today = days.get(day);
            today.setTotal(days.values().stream().mapToInt(Round::getStrokes).sum());
            today.setRounds(played);
            totals.add(today);
        }
        totals.sort(Comparator.comparingInt(Round::getTotal).thenComparing(Round::getName));
        int rank = 0;
        int previous = -1;
        for (int i = 0; i < totals.size(); i++) {
            Round round = totals.get(i);
            if (round.getTotal() != previous) {
                rank = i + 1;
            }
            previous = round.getTotal();
            round.setRank(rank);
        }
        return totals;
    }

    /**
     * Whether a day's standings decide its event: every single-day event, a series' last day
     *
     * @param day    The day
     * @param series The first day of the day's series, or null
     * @return True if the day's standings are final
     */
    private static boolean decides(int day, Integer series) {
        return series == null || day == series + seriesDays(series) - 1;
    }

    /**
     * Get the prize money for a place
     *
     * @param purse The event's purse
     * @param place The place, from 1
     * @return The amount
     */
    static long payout(long purse, int place) {
        if (place < 1) {
            return 0;
        }
        double rate = place <= PAYOUT_RATES.length ? PAYOUT_RATES[place - 1] : PAYOUT_RATES[PAYOUT_RATES.length - 1] * Math.pow(PAYOUT_DECAY, place - PAYOUT_RATES.length);
        return (long) (purse * rate);
    }

    /**
     * Rank and pay the days before today that are not settled yet
     *
     * @param vers  The game version
     * @param today Today's day
     */
    private void settle(String vers, int today) {
        for (TwEventEntity event : twEventRepository.findByVersAndEventDayLessThanAndSettledOnIsNullOrderByEventDay(vers, today)) {
            settle(vers, event);
        }
    }

    /**
     * Settle a day: if it decides its event, pay the purse on its final standings and count each player's event win
     * and top finishes. Settling again (a scorecard can arrive after the day ended) only applies the difference
     *
     * @param vers  The game version
     * @param event The day's event
     */
    private void settle(String vers, TwEventEntity event) {
        int day = event.getEventDay();
        if (decides(day, event.getSeriesStart())) {
            Map<Long, Round> standings = new HashMap<>();
            for (Round round : day(vers, day)) {
                standings.put(round.getPersonaId(), round);
            }
            Map<Long, TwEntryEntity> best = new HashMap<>();
            List<TwEntryEntity> entries = twEntryRepository.findCounted(vers, day, day);
            for (TwEntryEntity entry : entries) {
                best.merge(entry.getPersona().getId(), entry, (one, other) -> one.getStrokes() <= other.getStrokes() ? one : other);
            }
            for (TwEntryEntity entry : entries) {
                Round round = best.get(entry.getPersona().getId()) == entry ? standings.get(entry.getPersona().getId()) : null;
                Integer rank = round == null ? null : round.getRank();
                Long prize = round == null ? null : payout(round.getPurse(), round.getRank());
                if (!Objects.equals(rank, entry.getFinalRank()) || !Objects.equals(prize, entry.getPrize())) {
                    TwPersonaStatsEntity stats = stats(entry.getPersona(), vers);
                    addFinish(stats, entry.getFinalRank(), entry.getPrize(), -1);
                    addFinish(stats, rank, prize, 1);
                    twPersonaStatsRepository.save(stats);
                    entry.setFinalRank(rank);
                    entry.setPrize(prize);
                    twEntryRepository.save(entry);
                }
            }
        }
        event.setSettledOn(now());
        twEventRepository.save(event);
    }

    /**
     * Count a finish in a player's statistics, or take it back
     *
     * @param stats The player's statistics
     * @param rank  The place, or null for no finish
     * @param prize The prize money
     * @param sign  1 to add the finish, -1 to remove it
     */
    private static void addFinish(TwPersonaStatsEntity stats, Integer rank, Long prize, int sign) {
        if (rank == null) {
            return;
        }
        if (rank == 1) {
            stats.setEventWins(stats.getEventWins() + sign);
        }
        if (rank <= 10) {
            stats.setTop10(stats.getTop10() + sign);
        }
        if (rank <= 25) {
            stats.setTop25(stats.getTop25() + sign);
        }
        stats.setEarnings(stats.getEarnings() + sign * prize);
    }

    /**
     * Get a player's earnings rank (MY RESUME), 0 when they have won nothing
     *
     * @param vers    The game version
     * @param persona The player
     * @return The rank
     */
    public int earningsRank(String vers, PersonaEntity persona) {
        ensureCalendar(vers);
        long earnings = twPersonaStatsRepository.findByPersonaIdAndVers(persona.getId(), vers).map(TwPersonaStatsEntity::getEarnings).orElse(0L);
        return earnings == 0 ? 0 : (int) twPersonaStatsRepository.getEarningsRank(vers, earnings);
    }

    /**
     * Get a player's results (qdb@w)
     *
     * @param vers    The game version
     * @param persona The player
     * @param start   The first day, or -1 for today
     * @param count   The number of days, at most 32
     * @return The player's place each day, as a list
     */
    public String results(String vers, PersonaEntity persona, int start, int count) {
        if (start < 0) {
            start = today();
        }
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (int i = 0; i < Math.clamp(count, 0, MAX_CALENDAR_DAYS); i++) {
            List<Round> board = day(vers, start + i);
            Optional<Round> mine = board.stream().filter(round -> round.getPersonaId() == persona.getId()).findFirst();
            if (mine.isPresent()) {
                Round leader = board.getFirst();
                entries.add(Map.entry(leader.getName(), TwTournamentCodec.result(mine.get().getDay(), mine.get().getRank(), leader.getStrokes(),
                        mine.get().getStrokes())));
            }
        }
        return TwTournamentCodec.list(entries);
    }

    /**
     * Get a board row's score to par: the running total against par for each round played
     * One par is estimated per course from every counted card, never a different par for each row
     *
     * @param vers  The game version
     * @param round The board row
     * @return E, or the difference with its sign
     */
    private String toPar(String vers, Round round) {
        return TwCardUtils.toPar(round.getTotal() - par(vers, round.getCourse()) * round.getRounds());
    }

    /**
     * Estimate a course's par: every bound errs high, so the smallest one its cards produce is its par
     *
     * @param vers   The game version
     * @param course The course index
     * @return The par
     */
    private int par(String vers, int course) {
        String key = vers + "/" + course;
        CachedPar cached = pars.get(key);
        if (cached != null && System.nanoTime() - cached.getAt() < PAR_CACHE_NANOS) {
            return cached.getValue();
        }
        int par = twEntryRepository.findCountedByCourse(vers, course).stream()
                .map(entry -> TwCardUtils.par(TwCardUtils.card(entry)))
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .min()
                .orElse(TwCardUtils.DEFAULT_PAR);
        pars.put(key, new CachedPar(par, System.nanoTime()));
        return par;
    }

    /**
     * Whether a leaderboard index is one of the tournament boards
     *
     * @param index The snap INDEX
     * @return True for the season's days, the weeks' money and the two text lists
     */
    public static boolean isTournamentBoard(int index) {
        return index >= BOARD_SEASON_FIRST && index <= BOARD_SEASON_LAST || index >= BOARD_WEEK_MONEY_FIRST && index <= BOARD_WEEK_MONEY_LAST
                || index == BOARD_LAST_WINNERS || index == BOARD_MONEY_LEADERS;
    }

    /**
     * Get a page of a tournament leaderboard (snap)
     *
     * @param vers        The game version
     * @param index       The board: a day of the season, a week's money, last week's winners or the weekly money leaders
     * @param seasonFirst The season's first day, as sent to the client with ufpvt, or null if it has not asked yet
     * @param start       The first row (from 0)
     * @param count       The number of rows
     * @param find        A player name to show alone, or empty
     * @return The rows, best first
     */
    public List<TwStanding> board(String vers, int index, Integer seasonFirst, int start, int count, String find) {
        ensureCalendar(vers);
        int first = seasonFirst == null ? today() - SEASON_DAYS_BEFORE_TODAY : seasonFirst;
        List<TwStanding> rows = new ArrayList<>();
        if (index >= BOARD_SEASON_FIRST && index <= BOARD_SEASON_LAST) {
            for (Round round : day(vers, first + index - BOARD_SEASON_FIRST)) {
                long points = Math.max(0, ROUND_SORT_BASE * round.getRounds() - round.getTotal());
                rows.add(new TwStanding(round.getName(), round.getRank(), points, null, toPar(vers, round)));
            }
        } else if (index >= BOARD_WEEK_MONEY_FIRST && index <= BOARD_WEEK_MONEY_LAST) {
            int weekFirst = first + (index - BOARD_WEEK_MONEY_FIRST) * 7;
            rows.addAll(money(vers, weekFirst, weekFirst + 6));
        } else if (index == BOARD_LAST_WINNERS) {
            for (int day = today() - 1; day >= today() - 7; day--) {
                Optional<TwEventEntity> event = twEventRepository.findByVersAndEventDay(vers, day);
                if (event.isEmpty() || !decides(day, event.get().getSeriesStart())) {
                    continue;
                }
                for (Round round : day(vers, day)) {
                    if (round.getRank() == 1) {
                        rows.add(new TwStanding(round.getName(), rows.size() + 1, listDay(day), null, toPar(vers, round)));
                    }
                }
            }
        } else if (index == BOARD_MONEY_LEADERS) {
            int weekFirst = today() - 7;
            weekFirst -= weekFirst % 7;
            for (int day = weekFirst; day <= today(); day += 7) {
                List<TwStanding> week = money(vers, day, day + 6);
                if (!week.isEmpty()) {
                    TwStanding leader = week.getFirst();
                    rows.addFirst(new TwStanding(leader.getName(), 1, listDay(day), null, leader.getPacked()));
                }
            }
        }
        if (!find.isEmpty()) {
            return rows.stream().filter(row -> row.getName().equalsIgnoreCase(find)).limit(count).toList();
        }
        return rows.stream().skip(start).limit(count).toList();
    }

    /**
     * Get the prize money won in the events decided between two days, most first
     *
     * @param vers  The game version
     * @param first The first day
     * @param last  The last day
     * @return The rows, the money as text
     */
    private List<TwStanding> money(String vers, int first, int last) {
        Map<Long, Long> amounts = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (TwEntryEntity entry : twEntryRepository.findPrizes(vers, first, last)) {
            amounts.merge(entry.getPersona().getId(), entry.getPrize(), Long::sum);
            names.put(entry.getPersona().getId(), entry.getPersona().getPers());
        }
        List<Map.Entry<Long, Long>> sorted = amounts.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed().thenComparingLong(Map.Entry::getKey))
                .toList();
        List<TwStanding> rows = new ArrayList<>();
        long previous = -1;
        int rank = 0;
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Long, Long> amount = sorted.get(i);
            if (amount.getValue() != previous) {
                rank = i + 1;
            }
            previous = amount.getValue();
            rows.add(new TwStanding(names.get(amount.getKey()), rank, amount.getValue() / MONEY_SORT_UNIT, null,
                    String.format(Locale.US, "$%,d", amount.getValue())));
        }
        return rows;
    }

    /**
     * Get a day as the game's text lists number it
     *
     * @param day The day
     * @return The day counted from 2003
     */
    private static int listDay(int day) {
        return Math.clamp(day - LIST_EPOCH_DAY, 0, MAX_LIST_DAY);
    }
}
