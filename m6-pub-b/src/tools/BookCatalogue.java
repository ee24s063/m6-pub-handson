import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only view of books.json shared by the two tools.
 *
 * The catalogue is a flat JSON array of flat objects, so a small regex
 * parser is enough and keeps the default build free of a JSON library.
 * Each record keeps its original JSON text so tools can return it verbatim.
 */
public final class BookCatalogue {

    public static final Path DEFAULT_PATH = Path.of("books.json");

    public record Book(String id, String title, String author, int year, String genre, String json) {}

    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");

    private final Path path;

    public BookCatalogue(Path path) {
        this.path = path;
    }

    /** The file exactly as stored. */
    public String raw() {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    public List<Book> books() {
        List<Book> out = new ArrayList<>();
        Matcher m = OBJECT.matcher(raw());
        while (m.find()) {
            String obj = m.group();
            out.add(new Book(str(obj, "id"), str(obj, "title"), str(obj, "author"),
                    Integer.parseInt(num(obj, "year")), str(obj, "genre"), obj));
        }
        return out;
    }

    private static String str(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(obj);
        return m.find() ? m.group(1).replace("\\\"", "\"") : "";
    }

    private static String num(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(obj);
        if (!m.find()) throw new IllegalArgumentException("record has no numeric " + key + ": " + obj);
        return m.group(1);
    }
}
