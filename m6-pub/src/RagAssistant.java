import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Query-time RAG assistant. Takes a natural-language query, embeds it,
 * pulls the top-k nearest chunks from the {@link RepoIndexer}'s index,
 * formats them into the RAG prompt template, and calls the LLM.
 *
 * Session 6A: complete {@link #retrieve(String, int)} and
 * {@link #buildPrompt(String, List)}.  Do NOT edit {@link #answer}.
 */
public class RagAssistant {

    private final RepoIndexer indexer;
    private final Embedder embedder;
    private final LlmClient llm;
    private final Path promptTemplate;

    public RagAssistant(RepoIndexer indexer, Embedder embedder, LlmClient llm, Path promptTemplate) {
        this.indexer = indexer;
        this.embedder = embedder;
        this.llm = llm;
        this.promptTemplate = promptTemplate;
    }

    public String answer(String query, int k) throws IOException {
        List<Chunk> hits = retrieve(query, k);
        String prompt = buildPrompt(query, hits);
        return llm.complete(prompt);
    }

    /**
     * Embeds the query, ranks every indexed chunk by cosine similarity,
     * and returns the top k (highest similarity first).
     *
     * Fails loudly if the query embedder is not the one the index was
     * built with -- vectors from different models are not comparable.
     */
    protected List<Chunk> retrieve(String query, int k) {
        if (!embedder.toString().equals(indexer.embedderId())) {
            throw new IllegalStateException("embedder mismatch: index built with "
                    + indexer.embedderId() + ", querying with " + embedder);
        }
        float[] q = embedder.embed(query);
        record Scored(Chunk chunk, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Chunk c : indexer.getIndex()) {
            scored.add(new Scored(c, cosine(q, c.embedding())));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Chunk> top = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            top.add(scored.get(i).chunk());
        }
        return top;
    }

    /** Cosine similarity; normalises explicitly since real embedders may not return unit vectors. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dim mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0.0 : dot / Math.sqrt(na * nb);
    }

    /**
     * Loads the template from {@link #promptTemplate} and substitutes
     * {@code {query}} and {@code {context}}; the context is each hit as a
     * "source:start-end" line followed by its content.
     */
    protected String buildPrompt(String query, List<Chunk> hits) throws IOException {
        String template = Files.readString(promptTemplate);
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            Chunk c = hits.get(i);
            context.append("[").append(i + 1).append("] ")
                   .append(c.source()).append(':').append(c.startLine())
                   .append('-').append(c.endLine()).append('\n')
                   .append(c.content().strip()).append("\n\n");
        }
        // Single pass so a placeholder appearing inside the query or a chunk is left alone.
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            if (template.startsWith("{query}", i)) {
                out.append(query);
                i += "{query}".length();
            } else if (template.startsWith("{context}", i)) {
                out.append(context.toString().strip());
                i += "{context}".length();
            } else {
                out.append(template.charAt(i++));
            }
        }
        return out.toString();
    }
}
