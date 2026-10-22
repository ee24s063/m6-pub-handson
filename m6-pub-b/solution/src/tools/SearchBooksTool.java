import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session 6B solution.
 * Argument format: {@code author|year_from|year_to}, any part may be
 * empty.
 *
 * The parser is minimal on purpose -- the sample catalogue
 * ({@code books.json}) uses a flat JSON-array of flat JSON-objects, so
 * a line-oriented regex scan is enough and keeps the lab independent of
 * any JSON library.
 */
public class SearchBooksTool implements Tool {

    private final Path catalogue;

    public SearchBooksTool() {
        this(Path.of("books.json"));
    }

    public SearchBooksTool(Path catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "search_books";
    }

    @Override
    public String call(String arg) {
        try {
            return filter(arg, Files.readString(catalogue));
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }

    static String filter(String arg, String json) {
        String[] parts = (arg == null ? "" : arg).split("\\|", -1);
        String author = parts.length > 0 ? parts[0].trim() : "";
        int yFrom = parse(parts, 1, Integer.MIN_VALUE);
        int yTo   = parse(parts, 2, Integer.MAX_VALUE);

        // Each record occupies a single line in books.json; splitting
        // the array by },{ gives us the per-record JSON fragments.
        List<String> matched = new ArrayList<>();
        Pattern recPat = Pattern.compile("\\{[^}]+\\}");
        Matcher m = recPat.matcher(json);
        while (m.find()) {
            String rec = m.group();
            if (!author.isEmpty() && !containsField(rec, "author", author)) continue;
            int year = fieldInt(rec, "year");
            if (year < yFrom || year > yTo) continue;
            matched.add(rec);
        }
        return "[" + String.join(",", matched) + "]";
    }

    private static int parse(String[] parts, int i, int fallback) {
        if (parts.length <= i || parts[i].isBlank()) return fallback;
        try { return Integer.parseInt(parts[i].trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static boolean containsField(String rec, String key, String needle) {
        Matcher m = Pattern
            .compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(rec);
        return m.find() && m.group(1).toLowerCase().contains(needle.toLowerCase());
    }

    private static int fieldInt(String rec, String key) {
        Matcher m = Pattern
            .compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
            .matcher(rec);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }
}
