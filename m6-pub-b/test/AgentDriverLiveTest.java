import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * The handout's second construction site: the same agent as
 * {@link AgentDriverTest}, but built with {@code new OllamaLlm()} instead
 * of {@code new StubLlmClient()}.
 *
 * Skipped (not failed) when no Ollama daemon is listening on
 * localhost:11434, so {@code make test} still runs offline. The model's
 * output is not deterministic even at temperature 0, so the assertions
 * check the agent's behaviour (cheap tool first, clean stop, answer
 * consistent with the catalogue) rather than exact text.
 */
public class AgentDriverLiveTest {

    private static final String QUESTION_A = "How many books did Ursula Le Guin write in the 1970s?";

    static boolean ollamaReachable() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("http://localhost:11434/api/tags").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    public void run_registersBothTools_withOllama() {
        AgentDriver agent = new AgentDriver(new OllamaLlm(), Path.of("prompts/react.txt"), 3);
        assertTrue(agent.getTools().containsKey("list_books"));
        assertTrue(agent.getTools().containsKey("search_books"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    public void run_questionA_withOllama() throws IOException {
        assumeTrue(ollamaReachable(), "Ollama not running on localhost:11434; live test skipped");

        Path trace = Files.createTempFile("live-A", ".log");
        AgentDriver agent = new AgentDriver(new OllamaLlm(), Path.of("prompts/react.txt"), 6)
                .withTrace(trace);
        String transcript = agent.run(QUESTION_A);
        String log = Files.readString(trace);
        Files.deleteIfExists(trace);

        assertFalse(transcript.isBlank());
        assertNotEquals(AgentDriver.StopReason.LLM_ERROR, agent.getStopReason(), log);
        assertFalse(agent.getToolCalls().isEmpty(), "agent never called a tool:\n" + log);
        assertEquals("search_books", agent.getToolCalls().get(0).tool(),
                "expected the cheap targeted tool first:\n" + log);
        if (agent.getStopReason() == AgentDriver.StopReason.FINAL_ANSWER) {
            // Only The Dispossessed (1974) qualifies; 1968/1969 are the 1960s.
            assertTrue(agent.getFinalAnswer().matches("(?s).*\\b1\\b.*"),
                    "wrong answer '" + agent.getFinalAnswer() + "':\n" + log);
        }
    }
}
