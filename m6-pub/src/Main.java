import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * End-to-end driver for Session 6A:
 *   1. index sample-repo/;
 *   2. for each question in {@link #QUESTIONS}, retrieve the top-k
 *      chunks and ask the LLM for an answer.
 *
 * Students add their own questions to {@link #QUESTIONS} (task 5 of
 * handson6.tex) and re-run with {@code make run}.
 *
 * The default build uses {@link StubEmbedder} + {@link StubLlmClient}
 * so the pipeline runs offline.  Once you have run
 * {@code make langchain4j} and copied {@code example/OllamaLlm.java}
 * into {@code src/}, swap the two construction lines below.
 */
public class Main {

    /**
     * Task (5): add nine more questions targeting the sample-repo/
     * library-management domain (books, members, loans, fines).
     */
    private static final String[] QUESTIONS = {
        "Where is the fine for an overdue loan computed?",
        "What class holds the catalogue of books, and how is it indexed?",
        "How does the system decide a loan has been returned?",
        "What is the maximum fine the policy allows?",
        "Which methods write to the loan registry?",
        "How many books can a member have on loan at the same time?",
        "How long is the standard loan term before a loan becomes overdue?",
        "How does searching the catalogue by title work?",
        "When are two Book objects considered equal?",
        "How are loans counted per genre for reporting?",
        // Task 8: answer is NOT in the corpus (the repo uses no web framework).
        "How does the Spring Boot REST controller authenticate users with JWT tokens?",
    };

    /** Default 8 (task 5); override with the TOP_K environment variable. */
    private static final int TOP_K = System.getenv("TOP_K") == null
            ? 8 : Integer.parseInt(System.getenv("TOP_K"));

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "sample-repo");

        // -----------------------------------------------------------
        // 1. Pipeline construction.
        // -----------------------------------------------------------
        // Real models by default; STUB=1 runs fully offline with the stubs.
        boolean stub = "1".equals(System.getenv("STUB"));
        Embedder    emb = stub ? new StubEmbedder()  : new MiniLmEmbedder();
        LlmClient   llm = stub ? new StubLlmClient() : new OllamaLlm();
        // Task 7: run with CHUNKER=lines to use the naive 50-line chunker.
        RepoIndexer.ChunkMode mode = "lines".equalsIgnoreCase(System.getenv("CHUNKER"))
                ? RepoIndexer.ChunkMode.LINES : RepoIndexer.ChunkMode.METHOD;
        RepoIndexer idx = new RepoIndexer(emb, mode);

        // -----------------------------------------------------------
        // 2. Index the corpus; report count + wall-clock time.
        // -----------------------------------------------------------
        Instant t0 = Instant.now();
        idx.indexDirectory(dir);
        Duration elapsed = Duration.between(t0, Instant.now());
        System.out.printf("Indexed %d chunk(s) from %s in %d ms [chunker=%s, embedder=%s, llm=%s]%n%n",
                idx.size(), dir, elapsed.toMillis(), idx.mode(), idx.embedderId(), llm);

        // -----------------------------------------------------------
        // 3. Ask each question.  Print retriever hits + LLM answer.
        //    (retrieve() and answer() both re-rank; the redundancy is
        //    deliberate so the trace shows what the retriever picked.)
        // -----------------------------------------------------------
        RagAssistant ra = new RagAssistant(
                idx, emb, llm, Path.of("prompts/rag.txt"));
        for (String q : QUESTIONS) {
            System.out.println("=== Q: " + q);
            List<Chunk> hits = ra.retrieve(q, TOP_K);
            for (Chunk c : hits) {
                System.out.println("  hit: " + c.source()
                        + ":" + c.startLine() + "-" + c.endLine());
            }
            Instant a0 = Instant.now();
            String answer = ra.answer(q, TOP_K);
            System.out.println("--- A: " + answer.strip());
            System.out.printf("--- (%.1f s)%n%n",
                    Duration.between(a0, Instant.now()).toMillis() / 1000.0);
        }
    }
}
