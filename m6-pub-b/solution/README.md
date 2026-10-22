# Session 6B solution

Fills in the four TODOs in the starter:

- `AgentDriver.shouldStop(response, step)` -- stops when the LLM emits
  a `Final Answer:` line or when the step budget is exhausted.
- `AgentDriver.parseAction(response)` -- regex-extracts the first
  `Action: <name>` + `Action Input: <arg>` pair.
- `ListBooksTool.call(arg)` -- reads `books.json` and returns the
  file verbatim.  Greedy on purpose: it carries the whole catalogue.
- `SearchBooksTool.call(arg)` -- parses `author|year_from|year_to`
  from the argument, scans `books.json` with a flat regex, and
  returns only matching records.  Open-ended parts (`"|1990|"`,
  `"Asimov||"`) are supported.

To use:

    cp solution/src/*.java        src/
    cp solution/src/tools/*.java  src/tools/
    cp solution/test/*.java       test/
    make test

The solution tests verify both tools against the real `books.json`
and both agent helpers in isolation.

Switching to a real local LLM (Ollama):

The solution still runs against `StubLlmClient` so the ReAct loop is
offline-testable.  To wire langchain4j + Ollama in:

1. `make langchain4j` (one-time, Maven required).
2. Install and start Ollama; pull a model such as `llama3.1:8b`
   (see `handson6.tex` for the step-by-step).
3. Write an `OllamaLlm` that implements `LlmClient` and delegates
   `complete(String)` to
   `dev.langchain4j.model.ollama.OllamaChatModel#generate(String)`.
4. Change `new AgentDriver(new StubLlmClient(), ...)` to
   `new AgentDriver(new OllamaLlm(), ...)` in your `main`.

Nothing else about the loop changes.  No API keys involved;
everything runs on your machine.
