import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * search_books: targeted lookup by author and year range.
 *
 * Expected argument format: {@code author|year_from|year_to}
 * where empty parts mean "no constraint".  Example:
 * <pre>
 *   search_books("Ursula K. Le Guin|1970|1979")
 * </pre>
 * Any part may be left empty, e.g. {@code "|1990|2000"} for a
 * year-range query across all authors.  Year bounds are inclusive.
 *
 * Returns a count line ("N matching books:") followed by the matches as
 * a JSON array (each record verbatim from books.json; "[]" when nothing
 * matches).  A malformed argument
 * produces an "ERROR: ..." observation that restates the format, so the
 * agent can correct itself on the next step.
 *
 * Parsing is deliberately forgiving of what LLMs actually emit:
 * surrounding quotes/backticks, a wrapping {@code search_books(...)},
 * and {@code author=..., year_from=...} or JSON-style key/value pairs.
 * Author matching ignores case, punctuation and initials, so
 * "Ursula Le Guin" and "le guin" both match "Ursula K. Le Guin".
 */
public class SearchBooksTool implements Tool {

    static final String FORMAT = "expected author|year_from|year_to, e.g. Ursula K. Le Guin|1970|1979";

    private static final Pattern KEY_VALUE = Pattern.compile(
            "\"?(author|year_from|year_to)\"?\\s*[:=]\\s*\"?([^\",}]*)\"?", Pattern.CASE_INSENSITIVE);

    private final BookCatalogue catalogue;

    public SearchBooksTool() {
        this(new BookCatalogue(BookCatalogue.DEFAULT_PATH));
    }

    public SearchBooksTool(BookCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "search_books";
    }

    @Override
    public String call(String arg) {
        String[] parts;
        try {
            parts = parseArg(arg == null ? "" : arg);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage() + "; " + FORMAT;
        }
        Integer from, to;
        try {
            from = parseYear(parts[1], "year_from");
            to = parseYear(parts[2], "year_to");
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage() + "; " + FORMAT;
        }
        Set<String> wanted = nameTokens(parts[0]);

        List<String> hits = new ArrayList<>();
        for (BookCatalogue.Book b : catalogue.books()) {
            if (!wanted.isEmpty() && !nameTokens(b.author()).containsAll(wanted)) continue;
            if (from != null && b.year() < from) continue;
            if (to != null && b.year() > to) continue;
            hits.add(b.json());
        }
        String json = hits.isEmpty() ? "[]" : "[\n  " + String.join(",\n  ", hits) + "\n]";
        // An explicit count up front: small models miscount JSON arrays.
        return hits.size() + (hits.size() == 1 ? " matching book:\n" : " matching books:\n") + json;
    }

    /** Normalises the raw Action Input into exactly three parts: author, year_from, year_to. */
    static String[] parseArg(String raw) {
        String s = raw.strip();
        // search_books("...") or search_books(...)
        Matcher call = Pattern.compile("^\\w+\\s*\\((.*)\\)$", Pattern.DOTALL).matcher(s);
        if (call.matches()) s = call.group(1).strip();
        s = stripQuotes(s);

        if (!s.contains("|")) {
            Matcher kv = KEY_VALUE.matcher(s);
            String[] parts = {"", "", ""};
            boolean any = false;
            while (kv.find()) {
                any = true;
                int i = switch (kv.group(1).toLowerCase()) {
                    case "author" -> 0;
                    case "year_from" -> 1;
                    default -> 2;
                };
                parts[i] = kv.group(2).strip();
            }
            if (any) return parts;
            // A bare string with no separators is treated as an author-only query.
            return new String[] {s, "", ""};
        }
        String[] parts = s.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("got " + parts.length + " '|'-separated parts in '" + raw + "'");
        }
        for (int i = 0; i < 3; i++) parts[i] = stripQuotes(parts[i].strip());
        return parts;
    }

    private static String stripQuotes(String s) {
        while (s.length() >= 2 && "\"'`".indexOf(s.charAt(0)) >= 0
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    private static Integer parseYear(String s, String field) {
        if (s.isBlank() || s.equalsIgnoreCase("null") || s.equals("*")) return null;
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a year, got '" + s + "'");
        }
    }

    /** Lower-cased name words, punctuation and single-letter initials dropped. */
    static Set<String> nameTokens(String name) {
        return Arrays.stream(name.toLowerCase().split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() > 1)
                .collect(Collectors.toSet());
    }
}
