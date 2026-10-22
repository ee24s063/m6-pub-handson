import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Command-line driver for the Session 6B agent.
 *
 * <pre>
 *   make run ARGS='[options] "question"'
 *
 *   --steps N      step budget (max LLM calls), default 6
 *   --tokens N     token budget (estimated prompt+response tokens), default unlimited
 *   --prompt FILE  ReAct template, default prompts/react.txt
 *   --trace FILE   write the trace log here (e.g. traces/A-sharp.log)
 *   --stub         use StubLlmClient instead of Ollama
 * </pre>
 */
public class Main {

    public static void main(String[] args) throws Exception {
        int steps = 6;
        int tokens = AgentDriver.UNLIMITED;
        Path prompt = Path.of("prompts/react.txt");
        Path trace = null;
        boolean stub = false;
        StringBuilder question = new StringBuilder();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--steps" -> steps = Integer.parseInt(args[++i]);
                case "--tokens" -> tokens = Integer.parseInt(args[++i]);
                case "--prompt" -> prompt = Path.of(args[++i]);
                case "--trace" -> trace = Path.of(args[++i]);
                case "--stub" -> stub = true;
                default -> question.append(question.length() > 0 ? " " : "").append(args[i]);
            }
        }
        if (question.length() == 0) {
            System.err.println("usage: Main [--steps N] [--tokens N] [--prompt FILE] [--trace FILE] [--stub] question");
            System.exit(2);
        }

        LlmClient llm = stub ? new StubLlmClient() : new OllamaLlm();
        AgentDriver agent = new AgentDriver(llm, prompt, steps, tokens);
        if (trace != null) agent.withTrace(trace);

        Instant t0 = Instant.now();
        agent.run(question.toString());
        double secs = Duration.between(t0, Instant.now()).toMillis() / 1000.0;

        System.out.println("question    : " + question);
        System.out.println("prompt      : " + prompt);
        for (AgentDriver.ToolCall c : agent.getToolCalls()) {
            System.out.printf("  step %d -> %s(\"%s\")  [observation ~%d tok]%n",
                    c.step(), c.tool(), c.arg(), c.observationTokens());
        }
        System.out.println("stop reason : " + agent.getStopReason());
        System.out.println("llm calls   : " + agent.getStepsUsed() + " / budget " + steps);
        System.out.println("tokens used : ~" + agent.getTokensUsed()
                + (tokens == AgentDriver.UNLIMITED ? "" : " / budget " + tokens));
        System.out.println("final answer: " + agent.getFinalAnswer());
        System.out.printf("wall time   : %.1f s%n", secs);
        if (trace != null) System.out.println("trace       : " + trace);
    }
}
