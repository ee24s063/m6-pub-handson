import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Walks a directory of .java files, splits each file into method-level
 * chunks, embeds each chunk, and stores the result in an in-memory index.
 *
 * Session 6A: complete {@link #chunkFile(Path)} and {@link #embed(String)}.
 */
public class RepoIndexer {

    /** How files are split into chunks. METHOD is the real chunker; LINES is task 7's "bad" one. */
    public enum ChunkMode { METHOD, LINES }

    /** Window size for {@link ChunkMode#LINES}. */
    public static final int LINE_WINDOW = 50;

    private static final Pattern TYPE_KEYWORD =
            Pattern.compile("\\b(class|interface|enum|record)\\s+\\w+");

    private final Embedder embedder;
    private final ChunkMode mode;
    private final List<Chunk> index = new ArrayList<>();

    public RepoIndexer(Embedder embedder) {
        this(embedder, ChunkMode.METHOD);
    }

    public RepoIndexer(Embedder embedder, ChunkMode mode) {
        this.embedder = embedder;
        this.mode = mode;
    }

    /** Walks dir, indexing every *.java file below it. */
    public void indexDirectory(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                 .sorted()
                 .forEach(this::indexFile);
        }
    }

    private void indexFile(Path file) {
        try {
            List<Chunk> chunks = chunkFile(file);
            for (Chunk c : chunks) {
                float[] v = embed(c.content());
                index.add(c.withEmbedding(v));
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to index " + file, e);
        }
    }

    /**
     * Splits a Java file into method-level chunks with a brace-balanced
     * scanner. Each chunk is prefixed with the file path and the enclosing
     * type signature(s), and keeps the method's leading JavaDoc and
     * annotations. Each type also gets one "header" chunk (type JavaDoc,
     * signature, fields) so questions about a class as a whole -- rather
     * than one of its methods -- have something to match.
     *
     * Falls back to a single whole-file chunk if no members are detected.
     */
    protected List<Chunk> chunkFile(Path file) throws IOException {
        String text = Files.readString(file);
        if (mode == ChunkMode.LINES) {
            return chunkByLines(file, text, LINE_WINDOW);
        }
        List<Chunk> out = new ArrayList<>();
        String code = blankCommentsAndStrings(text);
        int[] lineStarts = lineStarts(text);
        scanBody(file, text, code, lineStarts, 0, text.length(), new ArrayList<>(), false, out);
        if (out.isEmpty()) {
            int lines = (int) text.lines().count();
            out.add(new Chunk(file.toString(), 1, lines, "// File: " + file + "\n" + text, null));
        }
        return out;
    }

    /** Task 7's deliberately naive chunker: fixed windows of {@code window} lines. */
    protected List<Chunk> chunkByLines(Path file, String text, int window) {
        List<String> lines = text.lines().toList();
        List<Chunk> out = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += window) {
            int end = Math.min(start + window, lines.size());
            String body = String.join("\n", lines.subList(start, end));
            out.add(new Chunk(file.toString(), start + 1, end,
                    "// File: " + file + "\n" + body, null));
        }
        return out;
    }

    /**
     * Walks the members of one brace-delimited body (or the whole file
     * when {@code enclosing} is empty), splitting it into segments that end
     * at a top-level ';' or at the '}' closing a top-level block.
     * Returns the text of the body's field declarations.
     */
    private String scanBody(Path file, String text, String code, int[] lineStarts,
                            int from, int to, List<String> enclosing,
                            boolean inInterface, List<Chunk> out) {
        StringBuilder fields = new StringBuilder();
        int segStart = from;
        int i = from;
        while (i < to) {
            char c = code.charAt(i);
            if (c != ';' && c != '{') {
                i++;
                continue;
            }
            int segEnd = c == '{' ? matchBrace(code, i, to) : i;
            String header = code.substring(segStart, i).replaceAll("\\s+", " ").trim();
            int memberStart = skipWhitespace(text, segStart, segEnd);

            Matcher type = TYPE_KEYWORD.matcher(header);
            if (c == '{' && type.find() && !header.contains("=") && !header.contains("new ")) {
                // Nested or top-level type: recurse, then insert its header chunk
                // ahead of its methods.
                List<String> inner = new ArrayList<>(enclosing);
                inner.add(header);
                int headerPos = out.size();
                String typeFields = scanBody(file, text, code, lineStarts, i + 1, segEnd, inner,
                        type.group(1).equals("interface"), out);
                String head = prefix(file, enclosing) + text.substring(memberStart, i).stripTrailing()
                        + " {\n" + typeFields + "    ...\n}";
                out.add(headerPos, new Chunk(file.toString(),
                        lineOf(lineStarts, memberStart), lineOf(lineStarts, i), head, null));
            } else if (!enclosing.isEmpty() && isMethod(header, c == '{', inInterface)) {
                String member = text.substring(memberStart, segEnd + 1);
                out.add(new Chunk(file.toString(),
                        lineOf(lineStarts, memberStart), lineOf(lineStarts, segEnd),
                        prefix(file, enclosing) + member, null));
            } else if (!enclosing.isEmpty() && c == ';') {
                fields.append("    ").append(text, memberStart, segEnd + 1).append('\n');
            }
            i = segEnd + 1;
            segStart = i;
        }
        return fields.toString();
    }

    private static boolean isMethod(String header, boolean hasBody, boolean inInterface) {
        int paren = header.indexOf('(');
        if (paren < 0) return false;
        String beforeParen = header.substring(0, paren);
        if (beforeParen.contains("=") || TYPE_KEYWORD.matcher(beforeParen).find()) return false;
        if (hasBody) return true;
        // Body-less declarations: interface methods or abstract/native methods.
        return inInterface || header.matches(".*\\b(abstract|native)\\b.*");
    }

    private static String prefix(Path file, List<String> enclosing) {
        StringBuilder sb = new StringBuilder("// File: ").append(file).append('\n');
        for (String sig : enclosing) {
            sb.append("// In: ").append(sig).append('\n');
        }
        return sb.toString();
    }

    /** Index of the '}' matching the '{' at {@code open}, scanning comment/string-free code. */
    private static int matchBrace(String code, int open, int limit) {
        int depth = 0;
        for (int i = open; i < limit; i++) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return limit - 1;
    }

    private static int skipWhitespace(String s, int from, int to) {
        while (from < to && Character.isWhitespace(s.charAt(from))) from++;
        return from;
    }

    /**
     * Returns a copy of {@code src} with comments and string/char literal
     * contents replaced by spaces (newlines kept), so braces and semicolons
     * inside them don't confuse the scanner. Offsets are preserved.
     */
    static String blankCommentsAndStrings(String src) {
        char[] out = src.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && src.charAt(i) != '\n') out[i++] = ' ';
            } else if (c == '/' && next == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (; i < end; i++) if (out[i] != '\n') out[i] = ' ';
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                for (i += 3; i < end - 3; i++) if (out[i] != '\n') out[i] = ' ';
                i = end;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
                    if (src.charAt(i) == '\\' && i + 1 < n) out[i++] = ' ';
                    out[i++] = ' ';
                }
                i++;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') starts.add(i + 1);
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 1-indexed line number of character offset {@code pos}. */
    private static int lineOf(int[] lineStarts, int pos) {
        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= pos) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
    }

    /**
     * Delegates to the configured {@link Embedder}; swap the binding at
     * construction time (e.g. a langchain4j all-MiniLM-L6-v2 adapter) to
     * get real semantic vectors.
     */
    protected float[] embed(String text) {
        return embedder.embed(text);
    }

    /**
     * Identifies the embedding model that produced this index, so the
     * query side can refuse to compare vectors from a different model.
     */
    public String embedderId() {
        return embedder.toString();
    }

    public ChunkMode mode() {
        return mode;
    }

    public List<Chunk> getIndex() {
        return index;
    }

    public int size() {
        return index.size();
    }
}
