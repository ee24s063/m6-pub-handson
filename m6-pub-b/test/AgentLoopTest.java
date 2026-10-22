import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public class AgentLoopTest {

    private static final Path PROMPT = Path.of("prompts/react.txt");

    /** Replays canned responses in order, then repeats the last one. */
    static class ScriptedLlm implements LlmClient {
        private final Deque<String> script;
        private String last = "";
        int calls = 0;

        ScriptedLlm(String... responses) {
            this.script = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public String complete(String prompt) {
            calls++;
            if (!script.isEmpty()) last = script.poll();
            return last;
        }
    }

    // ---------------------------------------------------------------- tools

    @Test
    public void listBooks_returnsWholeCatalogue() {
        String all = new ListBooksTool().call("");
        assertTrue(all.startsWith("["));
        assertEquals(30, all.split("\"id\"").length - 1);
    }

    @Test
    public void searchBooks_leGuin1970s_findsOnlyTheDispossessed() {
        String r = new SearchBooksTool().call("Ursula K. Le Guin|1970|1979");
        assertTrue(r.contains("The Dispossessed"), r);
        assertFalse(r.contains("The Left Hand of Darkness"), r);   // 1969
        assertFalse(r.contains("A Wizard of Earthsea"), r);        // 1968
    }

    @Test
    public void searchBooks_authorMatchIgnoresInitialsCaseAndPunctuation() {
        SearchBooksTool t = new SearchBooksTool();
        String expected = t.call("Ursula K. Le Guin||");
        assertEquals(expected, t.call("Ursula Le Guin||"));
        assertEquals(expected, t.call("le guin||"));
        assertEquals(3, expected.split("\"id\"").length - 1);
    }

    @Test
    public void searchBooks_openEndedYearRangeAcrossAuthors() {
        String r = new SearchBooksTool().call("|1990|1999");
        // Parable of the Sower 1993, Snow Crash 1992, Cryptonomicon 1999,
        // The God of Small Things 1997, A Suitable Boy 1993.
        assertEquals(5, r.split("\"id\"").length - 1, r);
    }

    @Test
    public void searchBooks_toleratesQuotesCallFormAndKeyValueArgs() {
        SearchBooksTool t = new SearchBooksTool();
        String expected = t.call("Ursula K. Le Guin|1970|1979");
        assertEquals(expected, t.call("\"Ursula K. Le Guin|1970|1979\""));
        assertEquals(expected, t.call("search_books(\"Ursula K. Le Guin|1970|1979\")"));
        assertEquals(expected, t.call("author=Ursula K. Le Guin, year_from=1970, year_to=1979"));
        assertEquals(expected, t.call("{\"author\": \"Ursula K. Le Guin\", \"year_from\": 1970, \"year_to\": 1979}"));
    }

    @Test
    public void searchBooks_badArgumentReturnsErrorWithFormat() {
        String r = new SearchBooksTool().call("Le Guin|the seventies|");
        assertTrue(r.startsWith("ERROR:"), r);
        assertTrue(r.contains("author|year_from|year_to"), r);
        assertEquals("0 matching books:\n[]", new SearchBooksTool().call("Nobody Atall||"));
        assertTrue(new SearchBooksTool().call("Ursula K. Le Guin|1970|1979").startsWith("1 matching book:\n["));
    }

    // ---------------------------------------------------------- parseAction

    @Test
    public void parseAction_readsActionAndInput() {
        AgentDriver d = new AgentDriver(new ScriptedLlm(), PROMPT, 3);
        Map.Entry<String, String> a = d.parseAction(
                "Thought: narrow it down\nAction: search_books\nAction Input: Ursula K. Le Guin|1970|1979\n");
        assertEquals("search_books", a.getKey());
        assertEquals("Ursula K. Le Guin|1970|1979", a.getValue());
    }

    @Test
    public void parseAction_acceptsCallFormAndMarkdown() {
        AgentDriver d = new AgentDriver(new ScriptedLlm(), PROMPT, 3);
        Map.Entry<String, String> a = d.parseAction("**Action:** `search_books(\"Le Guin|1970|1979\")`");
        assertEquals("search_books", a.getKey());
        assertEquals("\"Le Guin|1970|1979\"", a.getValue());
        assertNull(d.parseAction("Thought: I am just thinking"));
    }

    @Test
    public void parseAction_emptyInputDoesNotSwallowNextLine() {
        AgentDriver d = new AgentDriver(new ScriptedLlm(), PROMPT, 3);
        Map.Entry<String, String> a = d.parseAction(
                "Thought: x\nAction: list_books\nAction Input:\nFinal Answer: five books");
        assertEquals("list_books", a.getKey());
        assertEquals("", a.getValue());
    }

    @Test
    public void shouldStop_onFinalAnswerOrLastStep() {
        AgentDriver d = new AgentDriver(new ScriptedLlm(), PROMPT, 3);
        assertTrue(d.shouldStop("Thought: done\nFinal Answer: 1", 0));
        assertFalse(d.shouldStop("Thought: x\nAction: list_books\nAction Input: ", 0));
        assertTrue(d.shouldStop("Thought: x\nAction: list_books\nAction Input: ", 2));
        // An Action that comes before a (hallucinated) Final Answer is not a stop.
        assertFalse(d.shouldStop("Action: list_books\nAction Input:\nFinal Answer: 3", 0));
    }

    // ----------------------------------------------------------------- loop

    @Test
    public void run_searchThenAnswer_stopsOnFinalAnswer() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                "Thought: filter by author and decade\nAction: search_books\nAction Input: Ursula K. Le Guin|1970|1979",
                "Thought: one result\nFinal Answer: 1 (The Dispossessed, 1974)");
        AgentDriver d = new AgentDriver(llm, PROMPT, 5);
        String transcript = d.run("How many books did Ursula Le Guin write in the 1970s?");
        assertEquals(AgentDriver.StopReason.FINAL_ANSWER, d.getStopReason());
        assertEquals("1 (The Dispossessed, 1974)", d.getFinalAnswer());
        assertEquals(2, d.getStepsUsed());
        assertEquals(1, d.getToolCalls().size());
        assertTrue(transcript.contains("Observation: 1 matching book:\n[\n  {\"id\": \"b002\""), transcript);
    }

    @Test
    public void run_hallucinatedObservationIsDiscarded() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                "Thought: search\nAction: search_books\nAction Input: Ursula K. Le Guin|1970|1979\n"
                        + "Observation: [made-up result with 7 books]\nFinal Answer: 7",
                "Thought: ok\nFinal Answer: 1");
        AgentDriver d = new AgentDriver(llm, PROMPT, 5);
        String transcript = d.run("q");
        assertFalse(transcript.contains("made-up"), transcript);
        assertEquals("1", d.getFinalAnswer());
        assertEquals(1, d.getToolCalls().size());
    }

    @Test
    public void run_stepBudgetAbortsCleanly() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                "Thought: more\nAction: search_books\nAction Input: Isaac Asimov||",
                "Thought: more\nAction: search_books\nAction Input: Ray Bradbury||",
                "Thought: more\nAction: search_books\nAction Input: Neil Gaiman||");
        AgentDriver d = new AgentDriver(llm, PROMPT, 3);
        String transcript = d.run("never-ending question");
        assertEquals(AgentDriver.StopReason.STEP_BUDGET, d.getStopReason());
        assertEquals(3, llm.calls);
        assertEquals(2, d.getToolCalls().size());   // the 3rd action is not executed
        assertNull(d.getFinalAnswer());
        assertTrue(transcript.endsWith("Observation: (step budget exhausted; stopping)\n"), transcript);
    }

    @Test
    public void run_loopGuardBlocksRepeatedCallAndNudgesToAnswer() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                "Thought: search\nAction: search_books\nAction Input: Ursula K. Le Guin|1970|1979",
                "Thought: again\nAction: search_books\nAction Input: \"ursula k. le guin | 1970 | 1979\"",
                "Thought: ok\nFinal Answer: 1");
        AgentDriver d = new AgentDriver(llm, PROMPT, 5);
        String transcript = d.run("q");
        assertEquals(1, d.getToolCalls().size());   // the repeat did not re-run the tool
        assertEquals(1, d.getRepeatedCalls());
        assertTrue(transcript.contains("Observation: REPEATED CALL"), transcript);
        assertEquals(AgentDriver.StopReason.FINAL_ANSWER, d.getStopReason());
        assertEquals("1", d.getFinalAnswer());
    }

    @Test
    public void run_finalAnswerAfterRepeatedCallIsAccepted() throws IOException {
        ScriptedLlm llm = new ScriptedLlm(
                "Thought: search\nAction: search_books\nAction Input: |1990|1999",
                "Thought: again\nAction: search_books\nAction Input: |1990|1999\n\nFinal Answer: five books");
        AgentDriver d = new AgentDriver(llm, PROMPT, 6);
        d.run("q");
        assertEquals(AgentDriver.StopReason.FINAL_ANSWER, d.getStopReason());
        assertEquals("five books", d.getFinalAnswer());
        assertEquals(2, llm.calls);
    }

    @Test
    public void run_finalAnswerAfterNewCallIsNotAccepted() throws IOException {
        // A *new* action must run first: the answer written before seeing its result is a guess.
        ScriptedLlm llm = new ScriptedLlm(
                "Thought: search\nAction: search_books\nAction Input: Isaac Asimov||\n\nFinal Answer: 7",
                "Thought: ok\nFinal Answer: 2");
        AgentDriver d = new AgentDriver(llm, PROMPT, 6);
        d.run("q");
        assertEquals("2", d.getFinalAnswer());
        assertEquals(1, d.getToolCalls().size());
    }

    @Test
    public void run_tokenBudgetRefusesTheCallThatWouldOverspend() throws IOException {
        // list_books returns ~840 tokens; the second prompt carries it and blows a 900-token budget.
        ScriptedLlm llm = new ScriptedLlm(
                "Thought: get everything\nAction: list_books\nAction Input: ",
                "Thought: count\nFinal Answer: 1");
        AgentDriver d = new AgentDriver(llm, PROMPT, 10, 900);
        d.run("How many books did Ursula Le Guin write in the 1970s?");
        assertEquals(AgentDriver.StopReason.TOKEN_BUDGET, d.getStopReason());
        assertEquals(1, llm.calls);
        assertTrue(d.getTokensUsed() <= 900);
    }

    @Test
    public void run_tokenBudgetIsAHardCapEvenWithLongResponses() throws IOException {
        // Each response is ~250 tokens; the cap must hold after the response is counted.
        String longThought = "Thought: " + "x".repeat(1000) + "\nAction: search_books\nAction Input: ";
        ScriptedLlm llm = new ScriptedLlm(longThought + "Isaac Asimov||", longThought + "Ray Bradbury||",
                longThought + "Neil Gaiman||", longThought + "Neal Stephenson||");
        for (int budget : new int[] {700, 1200, 2000}) {
            AgentDriver d = new AgentDriver(llm, PROMPT, 10, budget);
            d.run("q");
            assertTrue(d.getTokensUsed() <= budget, "used " + d.getTokensUsed() + " > " + budget);
            assertEquals(AgentDriver.StopReason.TOKEN_BUDGET, d.getStopReason());
        }
    }

    @Test
    public void run_llmFailureStopsCleanly() throws IOException {
        LlmClient failing = prompt -> { throw new RuntimeException("timeout"); };
        AgentDriver d = new AgentDriver(failing, PROMPT, 3);
        String transcript = d.run("q");
        assertEquals(AgentDriver.StopReason.LLM_ERROR, d.getStopReason());
        assertTrue(transcript.contains("LLM call failed"), transcript);
    }

    @Test
    public void run_writesTraceFile() throws IOException {
        Path trace = Files.createTempFile("trace", ".log");
        ScriptedLlm llm = new ScriptedLlm("Thought: ok\nFinal Answer: 42");
        new AgentDriver(llm, PROMPT, 3).withTrace(trace).run("q");
        String log = Files.readString(trace);
        assertTrue(log.contains("stop reason : FINAL_ANSWER"), log);
        assertTrue(log.contains("final answer: 42"), log);
        Files.deleteIfExists(trace);
    }
}
