import java.nio.file.Path;
import java.util.List;

/**
 * Tasks 6/7: retrieval-only quality signal. For each question, is a
 * "right" chunk in the top-k? A question's ground truth is one or more
 * (file, line) locations, hand-labelled from sample-repo/; a retrieved
 * chunk counts as right if it comes from that file and its line range
 * covers that line. Line-based matching keeps the check fair across
 * chunkers (method chunks vs 50-line windows).
 *
 * No LLM involved -- run with {@code make eval} (CHUNKER=lines for task 7).
 */
public class RetrievalEval {

    record Target(String file, int line) {}

    record Case(String question, List<Target> targets) {}

    static final List<Case> CASES = List.of(
        new Case("Where is the fine for an overdue loan computed?",
                List.of(new Target("FinePolicy.java", 28))),
        new Case("What class holds the catalogue of books, and how is it indexed?",
                List.of(new Target("Catalog.java", 15))),
        new Case("How does the system decide a loan has been returned?",
                List.of(new Target("Loan.java", 42), new Target("Loan.java", 50),
                        new Target("Member.java", 52))),
        new Case("What is the maximum fine the policy allows?",
                List.of(new Target("FinePolicy.java", 35), new Target("FinePolicy.java", 10))),
        new Case("Which methods write to the loan registry?",
                List.of(new Target("LoanRegistry.java", 17), new Target("LibraryService.java", 39))),
        new Case("How many books can a member have on loan at the same time?",
                List.of(new Target("Member.java", 45))),
        new Case("How long is the standard loan term before a loan becomes overdue?",
                List.of(new Target("Loan.java", 53), new Target("Loan.java", 7))),
        new Case("How does searching the catalogue by title work?",
                List.of(new Target("Catalog.java", 31))),
        new Case("When are two Book objects considered equal?",
                List.of(new Target("Book.java", 51))),
        new Case("How are loans counted per genre for reporting?",
                List.of(new Target("ReportService.java", 36)))
    );

    public static void main(String[] args) throws Exception {
        int k = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        boolean stub = "1".equals(System.getenv("STUB"));
        Embedder emb = stub ? new StubEmbedder() : new MiniLmEmbedder();
        RepoIndexer.ChunkMode mode = "lines".equalsIgnoreCase(System.getenv("CHUNKER"))
                ? RepoIndexer.ChunkMode.LINES : RepoIndexer.ChunkMode.METHOD;
        RepoIndexer idx = new RepoIndexer(emb, mode);
        idx.indexDirectory(Path.of("sample-repo"));
        RagAssistant ra = new RagAssistant(idx, emb, new StubLlmClient(), Path.of("prompts/rag.txt"));

        System.out.printf("chunker=%s embedder=%s chunks=%d k=%d%n", mode, emb, idx.size(), k);
        int hits = 0, top1 = 0;
        double rrSum = 0.0;
        long contextChars = 0;
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            List<Chunk> top = ra.retrieve(c.question(), k);
            contextChars += ra.buildPrompt(c.question(), top).length();
            int rank = -1;
            for (int r = 0; r < top.size() && rank < 0; r++) {
                if (covers(top.get(r), c.targets())) rank = r + 1;
            }
            if (rank > 0) {
                hits++;
                rrSum += 1.0 / rank;
                if (rank == 1) top1++;
            }
            System.out.printf("Q%-2d %-5s rank=%-2s %s%n", i + 1, rank > 0 ? "HIT" : "MISS",
                    rank > 0 ? String.valueOf(rank) : "-", c.question());
        }
        int n = CASES.size();
        System.out.printf("hit-rate@%d = %d/%d = %.2f | hit@1 = %d/%d | MRR = %.2f"
                        + " | avg chunk = %.0f lines (%.1f%% of corpus per chunk)%n",
                k, hits, n, hits / (double) n, top1, n, rrSum / n,
                idx.getIndex().stream().mapToInt(c -> c.endLine() - c.startLine() + 1).average().orElse(0),
                100.0 / idx.size());
        // ~4 chars per token is the usual rough conversion for code/English.
        System.out.printf("avg prompt size @%d = %d chars (~%d tokens)%n",
                k, contextChars / n, contextChars / n / 4);
    }

    static boolean covers(Chunk chunk, List<Target> targets) {
        String src = chunk.source().replace('\\', '/');
        for (Target t : targets) {
            if (src.endsWith("/" + t.file())
                    && chunk.startLine() <= t.line() && t.line() <= chunk.endLine()) {
                return true;
            }
        }
        return false;
    }
}
