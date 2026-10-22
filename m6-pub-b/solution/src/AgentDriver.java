import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session 6B solution.  Fills in {@link #shouldStop(String, int)} and
 * {@link #parseAction(String)} and swaps the registered tools to
 * {@link ListBooksTool} + {@link SearchBooksTool} (defined in the
 * starter already).
 */
public class AgentDriver {

    private final LlmClient llm;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Path promptTemplate;
    private final int stepBudget;

    public AgentDriver(LlmClient llm, Path promptTemplate, int stepBudget) {
        this.llm = llm;
        this.promptTemplate = promptTemplate;
        this.stepBudget = stepBudget;
        registerTool(new ListBooksTool());
        registerTool(new SearchBooksTool());
    }

    public void registerTool(Tool t) {
        tools.put(t.name(), t);
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    public String run(String userQuery) throws IOException {
        String template = Files.readString(promptTemplate);
        StringBuilder scratch = new StringBuilder();
        String lastResponse = "";
        for (int step = 0; step < stepBudget; step++) {
            String prompt = template
                    .replace("{query}", userQuery)
                    .replace("{scratchpad}", scratch.toString());
            lastResponse = llm.complete(prompt);
            scratch.append(lastResponse).append('\n');
            if (shouldStop(lastResponse, step)) {
                break;
            }
            Map.Entry<String, String> action = parseAction(lastResponse);
            if (action == null) {
                scratch.append("Observation: (no action parsed; stopping)\n");
                break;
            }
            Tool t = tools.get(action.getKey());
            String obs = (t == null)
                    ? "ERROR: unknown tool " + action.getKey()
                    : t.call(action.getValue());
            scratch.append("Observation: ").append(obs).append('\n');
        }
        return scratch.toString();
    }

    /**
     * Stop as soon as the LLM has emitted a "Final Answer:" line, or
     * we have used up the step budget (-1 because {@link #run} will
     * increment one more time before the loop condition is tested).
     */
    protected boolean shouldStop(String lastResponse, int step) {
        if (lastResponse != null && lastResponse.contains("Final Answer:")) {
            return true;
        }
        return step >= stepBudget - 1;
    }

    private static final Pattern ACTION_LINE =
        Pattern.compile("^Action:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*$",
                        Pattern.MULTILINE);
    private static final Pattern INPUT_LINE =
        Pattern.compile("^Action Input:\\s*(.*)$", Pattern.MULTILINE);

    /**
     * Parse the first Action / Action Input pair out of the LLM reply.
     * Returns null if neither line is present (so {@link #run} can stop
     * cleanly).
     */
    protected Map.Entry<String, String> parseAction(String response) {
        if (response == null) return null;
        Matcher a = ACTION_LINE.matcher(response);
        if (!a.find()) return null;
        String name = a.group(1).trim();
        Matcher in = INPUT_LINE.matcher(response);
        String arg = in.find() ? in.group(1).trim() : "";
        return new AbstractMap.SimpleEntry<>(name, arg);
    }
}
