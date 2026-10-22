# Session 6B — Agentic mini-pipeline: reflection

**Setup.** `llama3.2:3b` via Ollama (temperature 0, max 300 output tokens) on an 8 GB Windows laptop. The catalogue is `books.json`: 30 books, about 3.3 KB, which is **~840 tokens** if `list_books` returns all of it. Token counts throughout are estimates (characters ÷ 4). Every number below comes from a trace in [`traces/`](traces/); see [`traces/README.md`](traces/README.md) for the index. All runs are reproducible with `./run-experiments.sh task4|task5|task6`.

Question A has exactly one correct answer: **1** (*The Dispossessed*, 1974). Le Guin's other two books in the catalogue are from 1968 and 1969, so a model that guesses or misreads the decade gets 3.

---

## What I built

| Piece | What it does |
|---|---|
| `ListBooksTool` | Returns `books.json` unchanged, about 840 tokens. |
| `SearchBooksTool` | Takes `author\|year_from\|year_to` (empty part = no limit; years inclusive) and returns a count line followed by the matching records as JSON. Author matching ignores case, punctuation and initials, so "Ursula Le Guin" matches "Ursula K. Le Guin". It also accepts the argument shapes small models actually produce: surrounding quotes, `search_books(...)`, `author=…`, JSON. If the argument is malformed it returns `ERROR: … expected author\|year_from\|year_to`, so the agent can correct itself. |
| `AgentDriver.parseAction` | Finds the first `Action:` / `Action Input:` pair. Also handles `Action: tool(arg)` and markdown bold or backticks. An empty `Action Input:` does not swallow the next line. |
| `AgentDriver.shouldStop` | Stops on a `Final Answer:` that appears before any `Action:`, or on the last step the budget allows. |
| Step budget | At most `stepBudget` LLM calls. The run ends with `STEP_BUDGET`, and the trace and transcript say so. |
| Token budget | A call is refused if its prompt plus a 300-token reserve for the reply would exceed the budget, so the budget is a hard limit and can never be overshot (unit-tested with long replies). |
| Trace log | Each step records prompt size, the raw reply, the parsed action, the observation and the running total. The summary gives the stop reason, call counts and tokens. |
| Additions needed in practice | Explained in "Driver fixes the runs forced on me" below. |

`make test` runs **24 tests**:

- 22 offline tests (`AgentLoopTest` and the seed `AgentDriverTest`) using a scripted fake LLM.
- 2 live tests in `AgentDriverLiveTest` that build the agent with `new OllamaLlm()` and run question A against the real model. They check that the agent used `search_books` first, stopped cleanly, and, if it answered, said 1. The question-A test is **skipped** when no Ollama daemon is listening on `localhost:11434`, so `make test` still works offline. With Ollama running, the full suite takes about a minute.

---

## Task 3: question A with sharp tool descriptions

Trace: [`traces/A-sharp.log`](traces/A-sharp.log)

| | |
|---|---|
| Tool called | `search_books` only |
| Arguments | `"Ursula Le Guin\|1970\|1979"`, exactly the intended filter |
| Iterations | 3 LLM calls: search → repeated the same search (blocked by the loop guard) → `Final Answer: 1` |
| Answer | **1, correct** |
| Tokens | about 1,350 |

Getting here took four iterations of the driver and prompt (traces `A0`–`A3`). The first run, with the **unmodified starter prompt** ([`A0-starter-prompt.log`](traces/A0-starter-prompt.log)), picked the right tool with the right arguments on step 1. It then never answered: it called `list_books` once and repeated the same search until the 6-step budget ended it, after about 5,900 tokens and no answer. The step budget was the only reason that run ended.

---

## Task 4: vague tool descriptions

Each variant changes only the tool-description block of `prompts/react.txt`:

- **v1** ([`react-vague1.txt`](prompts/react-vague1.txt)): the handout's change. `search_books` becomes "Search for books." Its argument format and the `list_books` cost warning stay.
- **v2** ([`react-vague2.txt`](prompts/react-vague2.txt)): both descriptions vague ("Get books." / "Search for books."). All cost hints and the empty-part example are removed; only the bare `author|year_from|year_to` line remains.
- **v3** ([`react-vague3.txt`](prompts/react-vague3.txt)): like v2, but the argument format is removed as well. Only the tool names are left.

Each variant was run on A, plus **C**: "Which books were published between 1990 and 1999?" C needs the open-author form `|1990|1999`; the correct answer is 5 books.

| Prompt | Q | First tool | All tool calls | Correct? | LLM calls | Tokens |
|---|---|---|---|---|---|---|
| sharp | A | `search_books` | search | ✅ 1 | 3 | ~1,350 |
| sharp | C | `search_books` | search → **list_books** | ✅ all 5 | 5 | ~5,080 |
| v1 | A | `search_books` | search → **list_books** | ✅ 1 | 4 | ~3,580 |
| v1 | C | `search_books` | search | ✅ all 5 | 2 | ~910 |
| v2 | A | `search_books` | search → **list_books** | ✅ 1 | 4 | ~3,160 |
| v2 | C | `search_books` | `search("year_from=1990\|year_to=1999")` → ERROR → **list_books** | ❌ asked the user for an author | 4 | ~3,340 |
| v3 | A | **`list_books`** | `list_books("year=1970")` → `search('author="Ursula K. Le Guin" AND year=1970')` | ✅ 1 | 3 | ~2,640 |
| v3 | C | **`list_books`** | `list_books("None")` | ❌ answered "1990-1999" | 3 | ~2,720 |

**What changed:**

- **The first tool choice only flipped when the argument format disappeared (v3).** With any description of `search_books` plus its format line, the model's *first* move was always the targeted search (6 of 6). With names alone, it went to `list_books` first (2 of 2), and passed it arguments `list_books` ignores (`"year=1970"`, `"None"`). Without a format to copy, it invented a query language (`author="…" AND year=1970`).
- **Removing the example of empty parts (v2) broke argument construction, not tool choice.** For C, the model wrote `year_from=1990|year_to=1999`, a mix of the field names and the separator. The tool's ERROR message restated the right format, but the 3B model fell back to `list_books` instead of retrying, then asked the user for an author.
- **v1, the handout's change, had almost no effect on these questions.** The argument format alone was enough for the model to see `search_books` as a filter.
- **Correctness:** sharp 2/2, v1 2/2, v2 1/2, v3 1/2.
- **`list_books` crept in even with sharp descriptions** (sharp-C, v1-A). It came *after* a correct search, as a "verify" step. The Thoughts say so explicitly ("I need to verify that there are no other books matching the filter", from `A-tokens2000.log`). The cost warning stops `list_books` being the first choice; it doesn't stop a nervous small model from double-checking.

The token totals don't fall neatly from sharp to v3 because of the noise discussed below. The *pattern* of tool calls is the reliable signal.

---

## Task 5: step budget of 3 on a harder question

Question H: *"Compare Ursula K. Le Guin, Octavia E. Butler and Neal Stephenson: for each author, in which decade did they publish the most books?"* The minimum is 3 searches (`Le Guin||`, `Butler||`, `Stephenson||`) plus a final answer, so 4 LLM calls, more than a budget of 3 allows.

| Budget | Trace | Result |
|---|---|---|
| `--steps 3` | [`H-steps3.log`](traces/H-steps3.log) | `list_books` → `search(Le Guin\|1970\|1979)` → about to `search(Butler\|1970\|1979)` → **`ABORT: step budget (3) exhausted before a final answer`**. Stop reason `STEP_BUDGET`, 2 tool calls, 3 LLM calls, final answer `null`. ✅ Clean abort, no exception. The 3rd Action was parsed but **not executed**. |
| `--steps 10` (control) | [`H-steps10.log`](traces/H-steps10.log) | Ran 6 of 10 steps and ended with `FINAL_ANSWER`, but the answer was *"The previous Observation contains the entire catalogue."* ❌ The model guessed a decade for each author (`Le Guin\|1970\|1979`, `Butler\|1970\|1979`, `Stephenson\|1990\|1999`) instead of querying each author without dates. It repeated `list_books`, the loop guard told it to answer now, and the forced answer was empty. |

The correct answer is Le Guin **1960s** (2 books, vs 1 in the 1970s), Butler a **tie** between the 1970s and 1990s (1 each), Stephenson **1990s** (2). The budget behaved exactly as intended. Planning a multi-author comparison is beyond this 3B model regardless of budget.

---

## Task 6: token budget

| Run | Trace | Result |
|---|---|---|
| A, `--tokens 800` | [`A-tokens800.log`](traces/A-tokens800.log) | Search ran (346 tokens used). The next call needed a prompt of about 384 tokens plus the 300 reserve, which would exceed 800, so it **fired before step 2**. `TOKEN_BUDGET`, 346/800 tokens used. ✅ |
| A, `--tokens 2000` | [`A-tokens2000.log`](traces/A-tokens2000.log) | This time the model went greedy: search, then **`list_books`** (+838 tokens), and the next prompt (~1,275) plus the reserve didn't fit. **Fired**: `TOKEN_BUDGET`, 808/2000. ✅ |
| A, `--tokens 2000`, repeated | [`run2`](traces/A-tokens2000-run2.log), [`run3`](traces/A-tokens2000-run3.log) | Search only, then `Final Answer: 1` at **~1,335/2000**. ✅ Did not fire. |
| C on v2, `--tokens 2000` | [`C-tokens2000-vague2.log`](traces/C-tokens2000-vague2.log) | Bad search, then `list_books` (+838), and the next call wouldn't fit. **Fired**: 585/2000. ✅ |

So it fires when expected. With the same question and the same budget, the budget is never hit on the cheap path and always hit once the agent pulls in the whole catalogue. For this catalogue, a budget of about 1.5–2k tokens effectively bans `list_books`, which is a useful way to enforce the "prefer the cheap tool" policy from outside the model.

**A bug the experiment caught.** My first token-budget version only checked the *prompt* before each call. The first task-6 run ended at **~820/800**, over budget, because the reply is counted after the call. Reserving the maximum reply size (300 tokens, the same as the model's output cap) fixed it, and a test now checks the budget is never exceeded.

---

## Driver fixes the runs forced on me

Each of these came from a specific failed trace; old traces are kept under `traces/superseded/`:

1. **Discard model-written Observations.** The 3B model regularly continued past its Action and wrote its own `Observation:`, often with made-up results, plus a `Final Answer`. The driver cuts each reply at the first `Observation:` line. The traces record how much was dropped, up to ~770 characters per step.
2. **Put a count line at the top of `search_books` output** ("1 matching book:"). Without it, the model looked at a one-element JSON array and decided *"the filter must be too narrow"*, then kept widening the year range (`A1-sharp-no-count.log`).
3. **Loop guard.** An identical repeated tool call is not re-run. The observation instead says "REPEATED CALL … reply now with Final Answer". This turned A from never-finishing (`A2-sharp-no-loop-guard.log`) into a correct 3-step run.
4. **Accept a Final Answer written after a *repeated* call, but not after a *new* one.** The model often writes `Action: <same call again>` and then the correct `Final Answer` in the same reply. The repeat can't add information, so that answer only uses real observations and is accepted. After a *new* call, the answer was written before seeing the result, so it's a guess and is ignored (both cases tested).
5. **Parser bug.** `Action Input:\s*(.*)` let `\s*` run past a line break, so an empty input captured the next line, which here was the `Final Answer`, as the tool argument. Fixed by using `[ \t]*`, with a regression test.
6. **Output cap and LLM-error stop.** Ollama has no output limit by default. One generation ran for 5 minutes until the HTTP timeout and crashed the run. Now: `numPredict(300)`, a 2-minute timeout, and a clean `LLM_ERROR` stop reason.

---

## Reflection

### Which tool did the agent prefer, and why?

**For its first call, `search_books`, whenever the prompt described its argument format and the question was a single filter** (6 of 6 task-4 runs with sharp, v1 and v2; every A and C run with the sharp prompt). The exception is H, which needs data on three authors: both H runs opened with `list_books`. That is arguably a sensible choice for a question that isn't a single filter, and it's exactly what the description allows ("use only if the query cannot be expressed as a filter"). I think the format line, not the cost warning, is what did it. v1 removed "returns only matching books" and changed nothing; v3 removed the format and the first choice flipped to `list_books` every time. A question like "Le Guin in the 1970s" maps almost directly onto `author|year_from|year_to`. A small model mostly fills templates, and the format line is a template to fill.

**But it used `list_books` at some point in 6 of 8 task-4 runs**, usually *after* a correct search, as reassurance. Its Thoughts give reasons such as "verify there are no other books" or "the filter must be too narrow", and they read like rationalisations rather than reasoning. In `A2` the Thought at step 3 correctly names *The Dispossessed* (1974), and the very next action is `list_books` anyway. That's the handout's pitfall: **the Thought describes what happened; it isn't evidence the action was right.**

### How much did tool-description quality matter?

A lot, but in a specific way:

- **The argument format mattered most.** Without it the model invented syntax (`author="…" AND year=1970`) and passed arguments to a tool that ignores them.
- **Examples mattered.** Removing the `"|1990|2000"` example (v2) was enough for the model to produce `year_from=1990|year_to=1999` and fail.
- **The description text itself ("Search for books." vs "returns only matching books") mattered little** for these two questions.
- Good tool *output* helped as much as good descriptions: the count line and an ERROR that restates the format. The model reads the tool's output much more literally than its description.

Correctness fell from 2/2 (sharp, v1) to 1/2 (v2, v3), and the failures were *confident wrong answers* ("1990-1999") or requests for information the user had already given. Nothing crashed.

### What would a production driver need on top of this?

- **Native tool calling instead of parsing free text.** Most of my fixes (fake Observations, Action and Final Answer in the same reply, markdown labels, the `\s*` bug) exist because the protocol is free text. A tool-calling API with JSON-schema arguments removes that whole class of bugs.
- **Argument validation against a schema**, with structured errors and a limit on retries per tool.
- **Real token accounting** from the provider's usage fields rather than chars ÷ 4, plus **budgets for cost and wall-clock time**, and per-tool budgets (e.g. at most one `list_books`).
- **Better loop and progress detection than "identical call"**: near-duplicate arguments, A→B→A oscillation, and "no new information in N steps". My guard sometimes forces a premature non-answer (H-steps10).
- **Answer checking**: check the final answer against the observations (e.g. "1" must match a count shown), or have the tool compute aggregates (`count_books`) so the model never counts.
- **Treat runs as non-deterministic.** The *same* step-2 prompt produced a repeated search in one run and `list_books` in another (`A-sharp` vs `A-tokens2000`), even at temperature 0. Evaluation needs repeated runs and reported rates, not single traces. Production needs retries and idempotent tools.
- **Structured, queryable traces** (JSON lines with run IDs, model and prompt versions) instead of text files, with redaction if tools return user data.
- **Timeouts, retries with backoff, and graceful degradation** when the model server is slow or down. `LLM_ERROR` is the minimal version of this.
- **Plan-then-act for multi-step questions.** H shows the model can't plan a multi-entity comparison step by step. A planner step, or a stronger model, is needed regardless of budget.

---

## Deviations and notes

- **Model:** `llama3.2:3b`, not `llama3.1:8b`, because the machine has 8 GB of RAM. Override with `OLLAMA_MODEL`.
- **Prompt:** [`prompts/react.txt`](prompts/react.txt) is my revised version. It gives the two output formats more explicitly and adds three rules (don't write Observations, don't repeat Actions, answer as soon as you can). The original is kept unchanged as [`react-starter.txt`](prompts/react-starter.txt). The tool-description block is identical apart from the task-4 variants.
- **Construction sites:** the handout names two places to switch from `new StubLlmClient()` to `new OllamaLlm()`.
  1. **The test site.** The seed `test/AgentDriverTest.java` is unchanged, because it checks for the stub's `"STUB RESPONSE"` text and a literal swap would break it. Instead, `test/AgentDriverLiveTest.java` repeats its tests with `new OllamaLlm()`, and the live question-A test skips itself when Ollama isn't running.
  2. **The `Main` site.** The new `src/Main.java` uses `new OllamaLlm()` by default (`--stub` switches back).
- **Makefile:**
  - Same Windows classpath-separator fix as in 6A, plus a `run` target (`make run ARGS='--steps 3 "question"'`).
  - **Test classpath fix:** the starter's `test` target ran `java -cp "$(CP)" -jar junit.jar --class-path build`. `java -jar` *ignores* `-cp`, so the langchain4j jars never reached the tests. Any test that constructs `OllamaLlm` failed with `NoClassDefFoundError`. The full classpath now goes to JUnit's own `--class-path`.
- **Build requirement:** because `src/OllamaLlm.java` is part of the build, `make build` and `make test` need `make langchain4j` to have been run once, as in 6A.
- **Not used:** `solution/`. Everything here is my own implementation.
