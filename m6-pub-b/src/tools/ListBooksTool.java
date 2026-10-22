/**
 * list_books: returns the entire book catalogue, verbatim.
 *
 * Intentionally greedy -- the whole JSON file goes back as the tool's
 * reply.  Session 6B asks the agent to prefer the cheaper
 * {@link SearchBooksTool} when the query is specific enough.
 */
public class ListBooksTool implements Tool {

    private final BookCatalogue catalogue;

    public ListBooksTool() {
        this(new BookCatalogue(BookCatalogue.DEFAULT_PATH));
    }

    public ListBooksTool(BookCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    public String name() {
        return "list_books";
    }

    /** The argument is ignored. */
    @Override
    public String call(String arg) {
        return catalogue.raw().strip();
    }
}
