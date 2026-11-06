package eu.eahub.dirtysdk;

import eu.eahub.utils.TwCardUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TW04's tournament wire formats: calendar and result lists, and the scorecard sent after a round
 * Layout from OnlineTourn.c, see THIRD-PARTY-TW04.txt
 */
public final class TwTournamentCodec {

    public static final int MAX_LIST_ENTRIES = 105;
    public static final int MAJOR_ICON = 4; // gold star
    private static final int DEFAULT_ICON = 2;
    private static final int NAME_LENGTH = 32;
    private static final int KEY_OFFSET = 92;
    private static final int KEY_LENGTH = 16;
    private static final int DAY_OFFSET = 108;
    private static final int COURSE_OFFSET = 110;
    private static final int MIN_CARD_HEX = 224;
    private static final int MAX_CARD_HEX = 1024;
    private static final long MAX_STATISTIC = 100000;
    // The calendar icon for a date, by month * 100 + day of month
    private static final Map<Integer, Integer> ICONS = Map.ofEntries(
            Map.entry(101, 24), Map.entry(115, 8), Map.entry(214, 0), Map.entry(222, 16), Map.entry(301, 14), Map.entry(317, 19),
            Map.entry(326, 26), Map.entry(408, 12), Map.entry(422, 25), Map.entry(502, 6), Map.entry(505, 29), Map.entry(602, 21),
            Map.entry(620, 4), Map.entry(621, 9), Map.entry(704, 1), Map.entry(805, 17), Map.entry(815, 27), Map.entry(910, 13),
            Map.entry(1001, 7), Map.entry(1030, 23), Map.entry(1031, 22), Map.entry(1111, 18), Map.entry(1224, 28), Map.entry(1225, 20));

    private TwTournamentCodec() {
    }

    /**
     * Get the calendar icon of a date
     *
     * @param month      The month, from 1
     * @param dayOfMonth The day of the month
     * @param major      Whether the day belongs to a major
     * @return The icon index
     */
    public static int icon(int month, int dayOfMonth, boolean major) {
        return major ? MAJOR_ICON : ICONS.getOrDefault(month * 100 + dayOfMonth, DEFAULT_ICON);
    }

    /**
     * Encode a calendar entry
     *
     * @param day        The day, counted from the game's epoch
     * @param course     The course index
     * @param purse      The purse
     * @param conditions The course conditions bits
     * @param icon       The calendar icon
     * @return The 16 bytes of the entry
     */
    public static byte[] event(int day, int course, long purse, int conditions, int icon) {
        byte[] data = new byte[16];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(0, (int) purse).putInt(8, conditions).putShort(12, (short) day);
        data[14] = (byte) course;
        data[15] = (byte) icon;
        return data;
    }

    /**
     * Encode a result entry, a player's place on a day's board
     *
     * @param day     The day, counted from the game's epoch
     * @param rank    The player's place, from 1
     * @param best    The leader's strokes
     * @param strokes The player's strokes
     * @return The 12 bytes of the entry
     */
    public static byte[] result(int day, int rank, int best, int strokes) {
        byte[] data = new byte[12];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putShort(4, (short) day).putInt(8, rank - 1);
        data[6] = (byte) Math.min(255, best);
        data[7] = (byte) Math.min(255, strokes);
        return data;
    }

    /**
     * Encode named entries as the client's list format
     *
     * @param entries The entries, each a name and its data, at most 105
     * @return The count, then each name and its data, in hex
     */
    public static String list(List<Map.Entry<String, byte[]>> entries) {
        if (entries.size() > MAX_LIST_ENTRIES) {
            throw new IllegalArgumentException("Too many entries");
        }
        StringBuilder out = new StringBuilder(String.format("%02X", entries.size()));
        for (Map.Entry<String, byte[]> entry : entries) {
            String name = entry.getKey().replaceAll("[^ -~]", "?");
            name = name.substring(0, Math.min(NAME_LENGTH, name.length()));
            out.append(String.format("%02X", name.length())).append(name).append(HexFormat.of().withUpperCase().formatHex(entry.getValue()));
        }
        return out.toString();
    }

    /**
     * Decode and check a scorecard (5d0tr)
     *
     * @param hex The scorecard, in hex
     * @return The scorecard bytes
     * @throws IllegalArgumentException if the scorecard is malformed or does not add up
     */
    public static byte[] decodeCard(String hex) {
        if (hex == null || hex.length() < MIN_CARD_HEX || hex.length() > MAX_CARD_HEX || (hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Invalid result length");
        }
        byte[] data = HexFormat.of().parseHex(hex);
        Map<String, Long> fields = cardFields(data);
        long holes = fields.get("HOLES");
        if (holes < 1 || holes > 18 || !TwCardUtils.addsUp(fields)
                || fields.get("STROKES") < holes || fields.get("STROKES") > holes * 12
                || fields.get("DONE") > 1 || fields.get("QUIT") > 1) {
            throw new IllegalArgumentException("Invalid scorecard");
        }
        for (String metric : List.of("PUTTS", "GIR", "LPUT", "DRVS", "FRWY", "LDRV")) {
            if (fields.get(metric) > MAX_STATISTIC) {
                throw new IllegalArgumentException("Invalid golf statistic");
            }
        }
        return data;
    }

    /**
     * Get the scorecard fields (STROKES, HOLES, ...) of a decoded scorecard
     *
     * @param card The scorecard bytes
     * @return The scorecard fields, in the game's order
     */
    public static Map<String, Long> cardFields(byte[] card) {
        ByteBuffer in = ByteBuffer.wrap(card).order(ByteOrder.LITTLE_ENDIAN);
        Map<String, Long> fields = new LinkedHashMap<>();
        for (String key : TwCardUtils.CARD_FIELDS) {
            fields.put(key, Integer.toUnsignedLong(in.getInt()));
        }
        return fields;
    }

    /**
     * Get the start key a scorecard was played with
     *
     * @param card The scorecard bytes
     * @return The key, in hex
     */
    public static String cardKey(byte[] card) {
        return HexFormat.of().formatHex(Arrays.copyOfRange(card, KEY_OFFSET, KEY_OFFSET + KEY_LENGTH));
    }

    /**
     * Get the day a scorecard was played on
     *
     * @param card The scorecard bytes
     * @return The day, counted from the game's epoch
     */
    public static int cardDay(byte[] card) {
        return Short.toUnsignedInt(ByteBuffer.wrap(card).order(ByteOrder.LITTLE_ENDIAN).getShort(DAY_OFFSET));
    }

    /**
     * Get the course a scorecard was played on
     *
     * @param card The scorecard bytes
     * @return The course index
     */
    public static int cardCourse(byte[] card) {
        return Byte.toUnsignedInt(card[COURSE_OFFSET]);
    }
}
