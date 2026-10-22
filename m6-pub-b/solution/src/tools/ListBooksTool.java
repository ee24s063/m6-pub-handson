import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Session 6B solution: dump books.json verbatim.
 * Deliberately greedy -- the whole catalogue goes back as the reply.
 */
public class ListBooksTool implements Tool {

    private final Path catalogue;

    public ListBooksTool() {
        this(Path.of("books.json"));
    }

    public ListBooksTool(Path catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "list_books";
    }

    @Override
    public String call(String arg) {
        try {
            return Files.readString(catalogue);
        } catch (IOException e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
