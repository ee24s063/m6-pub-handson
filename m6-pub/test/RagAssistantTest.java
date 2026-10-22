import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class RagAssistantTest {

    private static final Path REPO = Path.of("sample-repo");
    private static final Path PROMPT = Path.of("prompts/rag.txt");

    private static RepoIndexer indexed(Embedder emb, RepoIndexer.ChunkMode mode) throws IOException {
        RepoIndexer idx = new RepoIndexer(emb, mode);
        idx.indexDirectory(REPO);
        return idx;
    }

    @Test
    public void chunker_splitsAtMethodBoundaries() throws IOException {
        RepoIndexer idx = indexed(new StubEmbedder(), RepoIndexer.ChunkMode.METHOD);
        // 8 files -> several dozen method chunks, not one per file.
        assertTrue(idx.size() > 30, "expected method-level chunks, got " + idx.size());
    }

    @Test
    public void methodChunk_hasPathClassSignatureAndJavadoc() throws IOException {
        RepoIndexer idx = indexed(new StubEmbedder(), RepoIndexer.ChunkMode.METHOD);
        Chunk isOverdue = idx.getIndex().stream()
                .filter(c -> c.content().contains("public boolean isOverdue("))
                .filter(c -> !c.content().contains("public Loan("))
                .findFirst().orElseThrow();
        assertTrue(isOverdue.content().startsWith("// File: "));
        assertTrue(isOverdue.content().contains("Loan.java"));
        assertTrue(isOverdue.content().contains("// In: public class Loan"));
        assertTrue(isOverdue.content().contains("True if the loan is currently past the standard term"));
        assertEquals(45, isOverdue.startLine());   // starts at the JavaDoc
        assertEquals(54, isOverdue.endLine());
    }

    @Test
    public void chunker_findsSecondTopLevelClassAndInterfaceMethods() throws IOException {
        RepoIndexer idx = indexed(new StubEmbedder(), RepoIndexer.ChunkMode.METHOD);
        assertTrue(idx.getIndex().stream().anyMatch(c ->
                c.content().contains("// In: class StandardFinePolicy implements FinePolicy")
                        && c.content().contains("CAP")));
        assertTrue(idx.getIndex().stream().anyMatch(c ->
                c.content().contains("// In: public interface FinePolicy")
                        && c.content().contains("BigDecimal computeFine(Loan loan, LocalDate today);")));
    }

    @Test
    public void lineChunker_usesFixedWindows() throws IOException {
        RepoIndexer idx = indexed(new StubEmbedder(), RepoIndexer.ChunkMode.LINES);
        for (Chunk c : idx.getIndex()) {
            assertTrue(c.endLine() - c.startLine() + 1 <= RepoIndexer.LINE_WINDOW);
        }
    }

    @Test
    public void retrieve_ranksByCosineSimilarity() throws IOException {
        StubEmbedder emb = new StubEmbedder();
        RepoIndexer idx = indexed(emb, RepoIndexer.ChunkMode.METHOD);
        // The query text equal to a chunk's content embeds to the same vector,
        // so that chunk must come back first.
        Chunk target = idx.getIndex().get(17);
        RagAssistant ra = new RagAssistant(idx, emb, new StubLlmClient(), PROMPT);
        List<Chunk> hits = ra.retrieve(target.content(), 5);
        assertEquals(5, hits.size());
        assertEquals(target.content(), hits.get(0).content());
    }

    @Test
    public void retrieve_failsLoudlyOnEmbedderMismatch() throws IOException {
        RepoIndexer idx = indexed(new StubEmbedder(), RepoIndexer.ChunkMode.METHOD);
        Embedder other = new Embedder() {
            public float[] embed(String text) { return new float[StubEmbedder.DIM]; }
            public String toString() { return "OtherEmbedder"; }
        };
        RagAssistant ra = new RagAssistant(idx, other, new StubLlmClient(), PROMPT);
        assertThrows(IllegalStateException.class, () -> ra.retrieve("fine", 3));
    }

    @Test
    public void buildPrompt_substitutesQueryAndContext() throws IOException {
        StubEmbedder emb = new StubEmbedder();
        RepoIndexer idx = indexed(emb, RepoIndexer.ChunkMode.METHOD);
        RagAssistant ra = new RagAssistant(idx, emb, new StubLlmClient(), PROMPT);
        List<Chunk> hits = idx.getIndex().subList(0, 2);
        String prompt = ra.buildPrompt("What is the cap?", hits);
        assertFalse(prompt.contains("{query}"));
        assertFalse(prompt.contains("{context}"));
        assertTrue(prompt.contains("What is the cap?"));
        Chunk first = hits.get(0);
        assertTrue(prompt.contains(first.source() + ":" + first.startLine() + "-" + first.endLine()));
    }
}
