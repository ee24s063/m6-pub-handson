import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.AbstractMap;
import java.util.Map;

/**
 * Session 6B solution test suite.  Verifies:
 *  - parseAction returns the first Action/Action Input pair;
 *  - shouldStop stops when the LLM emits "Final Answer:";
 *  - list_books returns a non-empty JSON array;
 *  - search_books filters by author and year range.
 */
public class SolutionTest {

    private static final String RESPONSE =
        "Thought: I need to count Le Guin books from the 70s.\n" +
        "Action: search_books\n" +
        "Action Input: Ursula K. Le Guin|1970|1979\n";

    @Test
    public void parseAction_extractsNameAndArg() throws Exception {
        AgentDriver agent = new AgentDriver(
            new StubLlmClient(), java.nio.file.Path.of("prompts/react.txt"), 3);
        Map.Entry<String, String> a = agent.parseAction(RESPONSE);
        assertNotNull(a);
        assertEquals("search_books", a.getKey());
        assertEquals("Ursula K. Le Guin|1970|1979", a.getValue());
    }

    @Test
    public void shouldStop_onFinalAnswer() throws Exception {
        AgentDriver agent = new AgentDriver(
            new StubLlmClient(), java.nio.file.Path.of("prompts/react.txt"), 10);
        assertTrue(agent.shouldStop("Thought: done.\nFinal Answer: 2", 0));
        assertFalse(agent.shouldStop("Thought: more work.", 0));
    }

    @Test
    public void shouldStop_onBudget() throws Exception {
        AgentDriver agent = new AgentDriver(
            new StubLlmClient(), java.nio.file.Path.of("prompts/react.txt"), 3);
        assertFalse(agent.shouldStop("Thought: still working.", 1));
        assertTrue(agent.shouldStop("Thought: still working.", 2));
    }

    @Test
    public void listBooks_returnsNonEmptyJson() {
        Tool t = new ListBooksTool();
        String out = t.call("");
        assertTrue(out.startsWith("[") && out.endsWith("]") || out.contains("["),
                   "expected a JSON array, got: " + out.substring(0, Math.min(60, out.length())));
        assertTrue(out.contains("Le Guin"), "catalogue should contain Le Guin");
    }

    @Test
    public void searchBooks_filtersByAuthorAndYear() {
        Tool t = new SearchBooksTool();
        String out = t.call("Ursula K. Le Guin|1970|1979");
        assertTrue(out.contains("Dispossessed"),
                   "Dispossessed (1974) should match; got: " + out);
        assertFalse(out.contains("Earthsea"),
                    "Wizard of Earthsea (1968) should NOT match; got: " + out);
    }

    @Test
    public void searchBooks_openEndedYearRange() {
        Tool t = new SearchBooksTool();
        String out = t.call("Isaac Asimov||");
        assertTrue(out.contains("Foundation"),
                   "year range empty should match all Asimov books; got: " + out);
    }
}
