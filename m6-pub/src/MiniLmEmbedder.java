import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

/**
 * Real semantic embedder: all-MiniLM-L6-v2 (384-dim), run in-process via
 * ONNX -- bundled in the langchain4j jar, so no network calls.
 *
 * toString() carries the model name and version; RepoIndexer records it
 * and RagAssistant.retrieve refuses to query an index built by a
 * different embedder.
 */
public class MiniLmEmbedder implements Embedder {

    public static final String MODEL_ID = "all-MiniLM-L6-v2@langchain4j-0.36.0";

    private final EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();

    @Override
    public float[] embed(String text) {
        return model.embed(text).content().vector();
    }

    @Override
    public String toString() {
        return "MiniLmEmbedder[" + MODEL_ID + ", dim=" + model.dimension() + "]";
    }
}
