import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;

import java.time.Duration;

/**
 * LlmClient backed by a locally running Ollama daemon (see handson6.pdf).
 * Defaults to llama3.2:3b, which fits an 8 GB machine; override with the
 * OLLAMA_MODEL environment variable (e.g. llama3.1:8b on 16 GB).
 */
public class OllamaLlm implements LlmClient {

    private final String modelName;
    private final ChatLanguageModel model;

    public OllamaLlm() {
        String env = System.getenv("OLLAMA_MODEL");
        this.modelName = env == null || env.isBlank() ? "llama3.2:3b" : env;
        this.model = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName(modelName)
                .temperature(0.0)
                // A ReAct turn is a few lines. Without a cap, Ollama's default is
                // unlimited output and a degenerate generation can run for minutes.
                .numPredict(300)
                .timeout(Duration.ofMinutes(2))
                .build();
    }

    @Override
    public String complete(String prompt) {
        return model.generate(prompt);
    }

    @Override
    public String toString() {
        return "OllamaLlm[" + modelName + ", temperature=0]";
    }
}
