import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal ReAct-style agent loop.
 *
 * Each turn we send the LLM the running scratchpad ("Thought/Action/
 * Observation" transcript so far) plus the user's original query, parse
 * exactly one Action out of the LLM's response, call the corresponding
 * tool, append the Observation, and loop.
 *
 * Two hard budgets bound every run:
 *  - a step budget: at most {@code stepBudget} LLM calls;
 *  - a token budget: an LLM call is refused unless its prompt plus the
 *    maximum response size ({@link #RESPONSE_RESERVE}) still fits in
 *    {@code tokenBudget}, so the estimated total over all calls never
 *    exceeds it.
 * Either one ends the run cleanly with a labelled {@link StopReason}
 * instead of an exception, and the trace log records why.
 */
public class AgentDriver {

    /** Why the last {@link #run} ended. */
    public enum StopReason { FINAL_ANSWER, STEP_BUDGET, TOKEN_BUDGET, NO_ACTION, LLM_ERROR }

    /** One tool invocation, for reporting. */
    public record ToolCall(int step, String tool, String arg, int observationTokens) {}

    public static final int UNLIMITED = Integer.MAX_VALUE;

    /** Max tokens one LLM response may use; matches OllamaLlm's numPredict cap. */
    public static final int RESPONSE_RESERVE = 300;

    private static final Pattern FINAL_ANSWER = Pattern.compile("Final Answer\\s*:\\s*(.*)", Pattern.DOTALL);
    // [ \t]* rather than \s* after the colon: an empty "Action Input:" must not
    // swallow the next line (e.g. a "Final Answer: ..." the model added).
    private static final Pattern ACTION = Pattern.compile("Action[ \\t]*:[ \\t]*([A-Za-z_][\\w-]*)(?:[ \\t]*\\((.*?)\\))?");
    private static final Pattern ACTION_INPUT = Pattern.compile("Action Input[ \\t]*:[ \\t]*(.*)");
    private static final Pattern FAKE_OBSERVATION = Pattern.compile("(?m)^\\s*Observation\\s*:");

    private final LlmClient llm;
    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final Path promptTemplate;
    private final int stepBudget;
    private final int tokenBudget;
    private Path traceFile;

    // Outcome of the most recent run().
    private StopReason stopReason;
    private int stepsUsed;
    private int tokensUsed;
    private String finalAnswer;
    private final List<ToolCall> toolCalls = new ArrayList<>();
    private int repeatedCalls;

    public AgentDriver(LlmClient llm, Path promptTemplate, int stepBudget) {
        this(llm, promptTemplate, stepBudget, UNLIMITED);
    }

    public AgentDriver(LlmClient llm, Path promptTemplate, int stepBudget, int tokenBudget) {
        if (stepBudget < 1) throw new IllegalArgumentException("stepBudget must be >= 1");
        this.llm = llm;
        this.promptTemplate = promptTemplate;
        this.stepBudget = stepBudget;
        this.tokenBudget = tokenBudget;
        registerTool(new ListBooksTool());
        registerTool(new SearchBooksTool());
    }

    public void registerTool(Tool t) {
        tools.put(t.name(), t);
    }

    public Map<String, Tool> getTools() {
        return tools;
    }

    /** Write a human-readable trace of every run to {@code file} (overwritten per run). */
    public AgentDriver withTrace(Path file) {
        this.traceFile = file;
        return this;
    }

    /**
     * Run the loop. Returns the final scratchpad (transcript of all
     * thoughts, actions, observations, plus the last LLM response).
     */
    public String run(String userQuery) throws IOException {
        String template = Files.readString(promptTemplate);
        StringBuilder scratch = new StringBuilder();
        StringBuilder trace = new StringBuilder();
        stopReason = null;
        stepsUsed = 0;
        tokensUsed = 0;
        finalAnswer = null;
        toolCalls.clear();
        repeatedCalls = 0;
        Set<String> seenCalls = new HashSet<>();

        trace.append("# ReAct trace  ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
             .append("\nllm         : ").append(llm)
             .append("\nprompt      : ").append(promptTemplate)
             .append("\nstep budget : ").append(stepBudget)
             .append("\ntoken budget: ").append(tokenBudget == UNLIMITED ? "unlimited" : tokenBudget)
             .append("\nquery       : ").append(userQuery).append("\n");
        try {
            for (int step = 0; step < stepBudget; step++) {
                String prompt = template
                        .replace("{query}", userQuery)
                        .replace("{scratchpad}", scratch.toString());
                int promptTokens = estimateTokens(prompt);
                trace.append("\n=== step ").append(step + 1).append('/').append(stepBudget)
                     .append("  prompt ~").append(promptTokens).append(" tok, used so far ~")
                     .append(tokensUsed).append(" tok\n");
                // Reserve room for the longest possible response, so the budget is a hard cap
                // rather than something one response can overshoot.
                if ((long) tokensUsed + promptTokens + RESPONSE_RESERVE > tokenBudget) {
                    stopReason = StopReason.TOKEN_BUDGET;
                    trace.append("ABORT: next call (prompt ~").append(promptTokens).append(" + response reserve ")
                         .append(RESPONSE_RESERVE).append(" tok) would exceed the token budget ")
                         .append(tokenBudget).append(" (used ~").append(tokensUsed).append(")\n");
                    scratch.append("Observation: (token budget exhausted; stopping)\n");
                    break;
                }

                String raw;
                try {
                    raw = llm.complete(prompt);
                } catch (RuntimeException e) {
                    // Timeouts / connection failures end the run like a budget does.
                    stopReason = StopReason.LLM_ERROR;
                    trace.append("ABORT: LLM call failed: ").append(e).append('\n');
                    scratch.append("Observation: (LLM call failed; stopping)\n");
                    break;
                }
                stepsUsed++;
                String response = cutAtFakeObservation(raw);
                tokensUsed += promptTokens + estimateTokens(raw);
                trace.append("--- LLM response").append(response.length() < raw.length()
                        ? " (truncated at a model-written 'Observation:'; " + (raw.length() - response.length())
                          + " chars dropped)" : "").append(":\n").append(response.strip()).append('\n');
                scratch.append(response.strip()).append('\n');

                if (shouldStop(response, step)) {
                    Matcher fa = FINAL_ANSWER.matcher(clean(response));
                    if (isFinalAnswer(response) && fa.find()) {
                        stopReason = StopReason.FINAL_ANSWER;
                        finalAnswer = fa.group(1).strip();
                        trace.append("STOP: final answer\n");
                    } else {
                        stopReason = StopReason.STEP_BUDGET;
                        trace.append("ABORT: step budget (").append(stepBudget)
                             .append(") exhausted before a final answer\n");
                        scratch.append("Observation: (step budget exhausted; stopping)\n");
                    }
                    break;
                }
                Map.Entry<String, String> action = parseAction(response);
                if (action == null) {
                    stopReason = StopReason.NO_ACTION;
                    trace.append("STOP: no action parsed\n");
                    scratch.append("Observation: (no action parsed; stopping)\n");
                    break;
                }
                String callKey = action.getKey() + "(" + normaliseArg(action.getValue()) + ")";
                if (!seenCalls.add(callKey)) {
                    // Loop guard: small models re-issue the same call instead of answering.
                    // Don't re-run the tool; tell the model it already has the result.
                    repeatedCalls++;
                    Matcher fa = FINAL_ANSWER.matcher(clean(response));
                    if (fa.find()) {
                        // The repeat can't add information, so a Final Answer written after it
                        // rests only on Observations already in the transcript: accept it.
                        stopReason = StopReason.FINAL_ANSWER;
                        finalAnswer = fa.group(1).strip();
                        trace.append("--- ACTION: ").append(callKey).append("  [REPEAT - tool not re-run]\n")
                             .append("STOP: final answer (written after a repeated call; accepted)\n");
                        break;
                    }
                    String obs = "REPEATED CALL: you already ran " + callKey
                            + " and its Observation is above. Do not call it again."
                            + " Reply now with 'Thought: ...' and 'Final Answer: ...'.";
                    trace.append("--- ACTION: ").append(callKey).append("  [REPEAT - tool not re-run]\n")
                         .append("--- OBSERVATION: ").append(obs).append('\n');
                    scratch.append("Observation: ").append(obs).append('\n');
                    continue;
                }
                Tool t = tools.get(action.getKey());
                String obs = (t == null)
                        ? "ERROR: unknown tool " + action.getKey() + "; available: " + tools.keySet()
                        : t.call(action.getValue());
                int obsTokens = estimateTokens(obs);
                toolCalls.add(new ToolCall(step + 1, action.getKey(), action.getValue(), obsTokens));
                trace.append("--- ACTION: ").append(action.getKey()).append("(\"").append(action.getValue())
                     .append("\")\n--- OBSERVATION (~").append(obsTokens).append(" tok):\n").append(obs).append('\n');
                scratch.append("Observation: ").append(obs).append('\n');
            }
        } finally {
            trace.append("\n=== summary\nstop reason : ").append(stopReason)
                 .append("\nllm calls   : ").append(stepsUsed)
                 .append("\ntool calls  : ").append(toolCalls.size()).append(' ').append(toolCallSummary())
                 .append("\nrepeats     : ").append(repeatedCalls).append(" (blocked by the loop guard)")
                 .append("\ntokens used : ~").append(tokensUsed).append(" (estimated, chars/4)")
                 .append("\nfinal answer: ").append(finalAnswer).append('\n');
            if (traceFile != null) {
                if (traceFile.getParent() != null) Files.createDirectories(traceFile.getParent());
                Files.writeString(traceFile, trace.toString());
            }
        }
        return scratch.toString();
    }

    /**
     * True once the LLM has emitted a "Final Answer:" (ahead of any Action
     * in the same response) or this was the last step the budget allows.
     */
    protected boolean shouldStop(String lastResponse, int step) {
        return isFinalAnswer(lastResponse) || step >= stepBudget - 1;
    }

    /**
     * Parses the first "Action: <name>" / "Action Input: <arg>" pair out of
     * {@code response}. Also accepts the call form "Action: name(arg)" and
     * markdown-decorated labels ("**Action:**"). Returns null if no Action
     * line is present.
     */
    protected Map.Entry<String, String> parseAction(String response) {
        if (response == null) return null;
        String text = clean(response);
        Matcher a = ACTION.matcher(text);
        // Skip "Action Input:" matching as an action named "Input".
        while (a.find()) {
            if (a.group(1).equals("Input")) continue;
            String name = a.group(1).strip();
            Matcher in = ACTION_INPUT.matcher(text);
            String arg;
            if (in.find(a.end())) {
                arg = in.group(1).strip();
            } else if (a.group(2) != null) {
                arg = a.group(2).strip();
            } else {
                arg = "";
            }
            return new AbstractMap.SimpleImmutableEntry<>(name, arg);
        }
        return null;
    }

    private static boolean isFinalAnswer(String response) {
        String text = clean(response);
        Matcher fa = FINAL_ANSWER.matcher(text);
        if (!fa.find()) return false;
        Matcher a = Pattern.compile("Action\\s*:").matcher(text);
        return !a.find() || a.start() > fa.start();
    }

    /** Small models often keep going and invent the Observation; keep only what precedes it. */
    static String cutAtFakeObservation(String response) {
        if (response == null) return "";
        Matcher m = FAKE_OBSERVATION.matcher(response);
        return m.find() ? response.substring(0, m.start()) : response;
    }

    /** Argument identity for the loop guard: quotes and whitespace don't make a call new. */
    static String normaliseArg(String arg) {
        String s = arg == null ? "" : arg.strip();
        while (s.length() >= 2 && "\"'`".indexOf(s.charAt(0)) >= 0 && s.charAt(s.length() - 1) == s.charAt(0)) {
            s = s.substring(1, s.length() - 1).strip();
        }
        return s.replaceAll("\\s*\\|\\s*", "|").toLowerCase();
    }

    /** Strip markdown emphasis/backticks the model may wrap labels in. */
    private static String clean(String s) {
        return s.replace("**", "").replace("`", "");
    }

    /** Rough token estimate (~4 chars per token for English/JSON). */
    static int estimateTokens(String s) {
        return s == null ? 0 : (s.length() + 3) / 4;
    }

    private String toolCallSummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ToolCall c : toolCalls) counts.merge(c.tool(), 1, Integer::sum);
        return counts.toString();
    }

    public StopReason getStopReason() { return stopReason; }
    public int getStepsUsed() { return stepsUsed; }
    public int getTokensUsed() { return tokensUsed; }
    public String getFinalAnswer() { return finalAnswer; }
    public List<ToolCall> getToolCalls() { return List.copyOf(toolCalls); }
    public int getRepeatedCalls() { return repeatedCalls; }
}
