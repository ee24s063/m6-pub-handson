# CS5013 Module 6: Hands-on Sessions 6A and 6B

*Building Software with LLM APIs.* Handout: [`handson6.pdf`](handson6.pdf)

| Session | Topic | Code | Result |
|---|---|---|---|
| **6A** | Retrieval-augmented Q&A over a local Java repository | [`m6-pub/`](m6-pub/) | 52 method-level chunks; retrieval hit-rate@8 = **10/10**; **8/10** answers fully correct (2 partial); the anti-hallucination guard demonstrated with and without |
| **6B** | ReAct agent with two tools, step and token budgets, trace logs | [`m6-pub-b/`](m6-pub-b/) | Question A answered correctly with the cheap tool in 3 calls; tool-description experiments; both budgets fire cleanly; 24 tests |

The per-session write-ups the handout asks for are also kept in each lab folder: [`m6-pub/6A-notes.md`](m6-pub/6A-notes.md) and [`m6-pub-b/6B-reflection.md`](m6-pub-b/6B-reflection.md). This file combines both.

**Contents**
- [Common setup](#common-setup)
- [Part A: Session 6A, RAG over a local Java repo](#part-a-session-6a-rag-over-a-local-java-repo)
- [Part B: Session 6B, agentic mini-pipeline](#part-b-session-6b-agentic-mini-pipeline)

---

## Common setup

Both labs use the same environment:

| | |
|---|---|
| Machine | Windows 11 laptop, 8 GB RAM, Java 21, GNU Make, Git Bash |
| LLM | `llama3.2:3b` via Ollama (local, temperature 0). The 3B model is used instead of the handout's `llama3.1:8b` because of the 8 GB RAM; override with `OLLAMA_MODEL=llama3.1:8b`. |
| Embedder (6A) | `all-MiniLM-L6-v2`, run in-process (ONNX) through langchain4j 0.36.0 |
| langchain4j | Installed per lab with `make langchain4j` (Apache Maven 3.9.9) |
| Windows fix (both labs) | The starter Makefiles joined classpath entries with `:`; Java on Windows needs `;`. Both Makefiles choose the separator by OS. |

How to run (from each lab folder, after `make langchain4j` once):

```sh
# 6A (m6-pub/)
make test                    # 10 unit tests
make run                     # index sample-repo/, ask the 11 questions
CHUNKER=lines make run       # task 7: 50-line chunker
make eval                    # retrieval hit-rate vs hand-labelled targets

# 6B (m6-pub-b/)
make test                    # 24 tests (the live Ollama test skips itself if Ollama is down)
make run ARGS='"How many books did Ursula Le Guin write in the 1970s?"'
./run-experiments.sh task4   # also task5, task6
```

---

## Part A: Session 6A, RAG over a local Java repo

**Setup used for every LLM run below**

| Component | Choice |
|---|---|
| Embedder | `all-MiniLM-L6-v2` (384-dim, in-process ONNX via langchain4j 0.36.0) — `MiniLmEmbedder` |
| LLM | `llama3.2:3b` via Ollama, temperature 0 — `OllamaLlm` (8 GB RAM machine, so the 3B model rather than the 8B in the handout) |
| Corpus | `sample-repo/` — 8 files, 9 types (incl. package-private `StandardFinePolicy`), 43 methods/constructors, ~400 lines |
| Retrieval | cosine similarity over an in-memory list, top-k = 8 unless stated |
| Hardware | Windows 11 laptop, CPU inference |

Raw output for every run is in [`runs/`](m6-pub/runs/); the exact prompt used for each experiment is saved next to it (`runs/rag-*.txt`).

How to reproduce:

```sh
make test                          # 10 offline unit tests (stubs only)
make run                           # 11 questions, method chunker, real models
CHUNKER=lines make run             # task 7: 50-line chunker
TOP_K=2 make run                   # any k
make eval                          # retrieval-only hit-rate vs hand-labelled targets
STUB=1 make run                    # fully offline, stub embedder + stub LLM
```

---

### 1. What was implemented

- **`RepoIndexer.chunkFile`** — a hand-rolled, brace-balanced chunker. It first blanks out comments and string/char literals (keeping character offsets), so braces inside them can't throw the depth count off. It then walks each type body and splits it into members that end at a top-level `;` or at the `}` that closes a block. It recurses into nested types and into a second top-level type in the same file.
  - **Method chunks**: each method or constructor becomes one chunk. The chunk starts with `// File: <path>` and `// In: <enclosing class signature>`, keeps the leading JavaDoc and annotations, and records accurate start and end lines.
  - **Type-header chunks**: each type also gets one chunk containing its JavaDoc, signature and field declarations. Without it, a question like "what class holds the catalogue?" has nothing to match, because the class-level JavaDoc ("In-memory catalogue keyed by ISBN") isn't part of any method.
  - Interface methods and `abstract` methods without a body are chunked too, so `FinePolicy.computeFine` gets a chunk.
  - If a file has no detectable members, the whole file becomes a single chunk.
  - `ChunkMode.LINES` is the task-7 "bad" chunker, using fixed 50-line windows.
- **`RagAssistant.retrieve`**: embeds the query, scores every chunk by cosine similarity (normalised explicitly, because real embedders don't always return unit vectors) and returns the top k.
  - **Model-version guard** (from the handout's pitfall box): the index records `embedder.toString()`, which for MiniLM includes the model name and langchain4j version. `retrieve` throws `IllegalStateException` if the query-side embedder differs. This is covered by a unit test.
- **`RagAssistant.buildPrompt`**: loads `prompts/rag.txt` and fills `{query}` and `{context}` in a single pass, so a placeholder that happens to appear inside a code chunk is left alone. Each hit is rendered as `[n] source:start-end` followed by the chunk content.
- **`Main`** has the 10 questions plus one off-corpus question, and uses the real models by default. The `CHUNKER`, `TOP_K` and `STUB` environment variables switch configurations.
- **`RetrievalEval`** (`make eval`) is a retrieval-only scorer. Each question has hand-labelled correct `(file, line)` locations, and a retrieved chunk counts as a hit if it covers one of them. Because it matches on lines, it is fair to both chunkers.
- **Tests**: `test/RagAssistantTest.java` adds 7 tests (chunk count, path/class/JavaDoc and line numbers on a known method, the second top-level class and interface methods, the line-window bound, cosine ranking, the embedder-mismatch guard, and placeholder substitution). `make test` gives 10/10 passing.

---

### 2. Indexer stats (task 3)

| Chunker | Embedder | Chunks | Index time |
|---|---|---|---|
| Starter stub (one chunk per file) | Stub (32-dim hash) | **8** | 15–18 ms |
| Method chunker | Stub | **52** | ~29 ms |
| Method chunker | MiniLM | **52** | 1378 ms cold (first run), 620–870 ms warm |
| 50-line chunker (task 7) | MiniLM | 12 | ~245 ms |

The 52 chunks are 43 method/constructor chunks plus 9 type-header chunks. That's 6.5× the per-file count, which is consistent with the ~5 methods per class in this repo, so the chunker really is splitting on methods and not merging them. The MiniLM indexing time is almost all ONNX model load and inference. The chunking itself takes under 30 ms.

---

### 3. Ten questions, answers, judgement (task 5)

Run: [`runs/run1-method-chunker.txt`](m6-pub/runs/run1-method-chunker.txt). Method chunker, k = 8, final prompt. Each answer took 1.6–3.0 s once the model was warm.

| # | Question | Top-8 retrieved chunks (rank of the "right" one in **bold**) | LLM answer (abridged) | Verdict |
|---|---|---|---|---|
| 1 | Where is the fine for an overdue loan computed? | Loan 45-54, LoanRegistry 34-42, ReportService 28-30, LibraryService 43-45, **FinePolicy 27-36 (#5)**, Loan 33-35, FinePolicy 9-13, Loan 37-39 | `StandardFinePolicy.computeFine` (in `FinePolicy`) multiplies days late by `PER_DAY` and applies `CAP` | ✅ Correct |
| 2 | What class holds the catalogue of books, and how is it indexed? | **Catalog 11-15 (#1)**, Book 6-11, Catalog 23-25, LibraryService 47-49, Catalog 19-21, Book 27-29, Catalog 27-40, LibraryService 30-41 | `Catalog`, a `HashMap` keyed by ISBN; `findByIsbn` looks books up | ✅ Correct |
| 3 | How does the system decide a loan has been returned? | **Loan 45-54 (#1)**, Loan 37-39, Loan 33-35, LoanRegistry 24-32, Loan 6-10, LoanRegistry 8-12, LoanRegistry 34-42, Loan 29-31 | Returned iff `returnedOn != null`; `isOverdue` returns false in that case | ✅ Correct (doesn't mention `markReturned` as the setter, but the rule is right) |
| 4 | What is the maximum fine the policy allows? | **FinePolicy 9-13 (#1)**, FinePolicy 27-36, FinePolicy 22-22, LibraryService 43-45, FinePolicy 15-15, Member 41-49, FinePolicy 17-19, Loan 6-10 | 500 rupees, the `CAP` constant `new BigDecimal("500.00")` | ✅ Correct (it says the "500 rupees" comment is on `StandardFinePolicy`; it's actually the `FinePolicy` interface JavaDoc, a minor slip) |
| 5 | Which methods write to the loan registry? | LoanRegistry 8-12, **LoanRegistry 16-18 (#2)**, ReportService 11-16, LoanRegistry 34-42, LoanRegistry 24-32, Loan 45-54, LoanRegistry 20-22, Loan 6-10 | `LoanRegistry.record` adds to `history` | ⚠️ Partial: `record` is the only direct writer, but `LibraryService.borrow` (the only caller) wasn't retrieved, so it isn't mentioned |
| 6 | How many books can a member have on loan at the same time? | **Member 41-49 (#1)**, Loan 6-10, Book 6-11, Loan 19-23, LibraryService 30-41, ReportService 32-43, Loan 25-27, LoanRegistry 24-32 | At most 5, enforced by `Member.addLoan` | ✅ Correct |
| 7 | How long is the standard loan term before a loan becomes overdue? | **Loan 45-54 (#1)**, Loan 6-10, ReportService 28-30, LoanRegistry 34-42, Loan 37-39, Loan 33-35, FinePolicy 27-36, Member 41-49 | 21 days, `DEFAULT_TERM_DAYS = 21` | ✅ Correct |
| 8 | How does searching the catalogue by title work? | Catalog 11-15, **Catalog 27-40 (#2)**, LibraryService 47-49, Catalog 23-25, Catalog 19-21, Book 6-11, Catalog 42-50, LibraryService 30-41 | Lower-cases the input and does a case-insensitive substring check in `searchByTitle`; notes "no tokenisation" | ⚠️ Mostly correct: it says the *input* contains a substring of the *title*, but it's the other way round (`title.contains(needle)`) |
| 9 | When are two Book objects considered equal? | **Book 47-55 (#1)**, Book 6-11, Book 19-25, Catalog 42-50, LibraryService 30-41, Catalog 19-21, Member 51-53, Book 27-29 | When they share an ISBN (`equals`) | ✅ Correct |
| 10 | How are loans counted per genre for reporting? | **ReportService 32-43 (#1)**, ReportService 11-16, LoanRegistry 8-12, ReportService 20-22, Loan 6-10, LoanRegistry 16-18, Member 41-49, LoanRegistry 24-32 | `ReportService.loansByGenre` uses `Map.merge(genre, 1, Integer::sum)` | ✅ Correct |

**Generation score: 8 fully correct, 2 partially correct, 0 wrong.**

#### Prompt iterations: an extra finding

The anti-hallucination wording mattered more than I expected with a 3B model. I went through three versions of `prompts/rag.txt`, all with the same retrieval:

| Prompt version | File | Result |
|---|---|---|
| v1: question *before* context, "reply **exactly**: I do not find that in the passages." | `runs/rag-strict.txt`, [`run0`](m6-pub/runs/run0-method-strict-prompt.txt) | **Refused 6 of 10** in-corpus questions (Q1, Q3, Q5, Q6, Q7, Q10), even though the right chunk was in the top 8 every time, at rank 1 for five of the six. Over-strict guard means false refusals. |
| v2: context *before* question, softer guard, "name the class or method" | `runs/rag-terse.txt`, [`run0b`](m6-pub/runs/run0b-method-terse-prompt.txt) | 1 refusal (Q6). Q4 answered just "CAP" and Q7 just "DEFAULT_TERM_DAYS": names, not values. |
| v3 (final): v2 + "1–3 sentences, state concrete values … and name the method" | `prompts/rag.txt` | Results in the table above |

The lesson is that the guard works in both directions. The same instruction that stops fabrication (see section 6) will, if phrased too absolutely, make a small model refuse when it has the answer in front of it.

---

### 4. Retrieval hit-rate (task 6)

"Right chunk" means a chunk covering the hand-labelled location(s) for that question (see `RetrievalEval.CASES`). Measured with `make eval`.

| Metric (method chunker, MiniLM) | Value |
|---|---|
| **hit-rate@8** | **10/10 = 1.00** |
| hit-rate@4 | 9/10 = 0.90 |
| hit-rate@2 | 9/10 = 0.90 |
| hit@1 | 7/10 |
| MRR@8 | 0.82 |

The only question outside the top 2 is Q1 ("where is the fine computed?"). The query words "overdue loan" pull in `Loan.isOverdue` and `LoanRegistry.overdueOn` first, and `computeFine` comes in at rank 5.

For comparison, the **stub embedder** (hash-based, no semantics) scores hit-rate@8 = 2/10 and MRR 0.04, i.e. chance level. A real embedder is what makes retrieval work at all.

---

### 5. "Bad chunker" experiment (task 7)

`CHUNKER=lines`: 50-line windows, giving 12 chunks of 34 lines on average. Runs: [`run2`](m6-pub/runs/run2-lines-chunker.txt) (k=8), [`run4`](m6-pub/runs/run4-lines-chunker-k2.txt) (k=2). The method chunker at k=2 is [`run3`](m6-pub/runs/run3-method-chunker-k2.txt).

#### Result

| | Method chunker | 50-line chunker |
|---|---|---|
| Chunks | 52 (avg 5 lines) | 12 (avg 34 lines) |
| Share of the corpus in one chunk | 1.9 % | 8.3 % |
| hit-rate@8 | 1.00 | 1.00 |
| hit-rate@2 | 0.90 | 0.90 |
| MRR@8 | 0.82 | 0.93 |
| Avg prompt size @ k=8 | **~812 tokens** | **~2,265 tokens (2.8×)** |
| Avg prompt size @ k=4 | ~444 tokens | ~1,105 tokens |
| Avg LLM latency @ k=8 | **~2.3 s** | **~14 s (6×)** |
| Correct answers @ k=8 | 8 + 2 partial | 9 + 1 with errors (see below) |
| Correct answers @ k=2 | 8/10 | 8/10 |

#### Honest reading: the hit-rate barely moved, and here is why

The handout expects the hit-rate to drop. **On this corpus it didn't.** The line-coverage hit-rate stayed at 1.00@8, and MRR actually went up. That's a property of the corpus, not evidence that 50-line chunks are good:

- **Every file is ≤ 66 lines**, so a 50-line window is basically a whole file. That's 12 chunks for 8 files.
- **At k=8 you retrieve 8 of 12 chunks, two-thirds of the entire repository.** Covering the right line is close to guaranteed. This is exactly the "crank k up to compensate for a bad chunker" pitfall: it scores well because it sends nearly everything.
- A whole-file chunk is an easy match for "which *class* does X" questions. On a large corpus (Assignment 11), files are hundreds of lines and k=8 is a tiny fraction, so a window would mix several unrelated methods and its embedding would match none of them well.

#### Where the bad chunker *does* hurt, even here

1. **Cost and latency**: 2.8× the prompt tokens and about 6× the answer time at the same k. This is the "good top-4 beats bad top-16" point from the pitfall box. The method chunker's top-4 (~444 tokens) already reaches 0.90 hit-rate.
2. **Unrelated code in the context produces hallucinations**:
   - Lines @ k=2, Q5: *"The `markReturned` method also writes to the loan registry by updating the `returnedOn` field"*. **Wrong.** The `Loan.java` window was retrieved alongside `LoanRegistry`, and the model mixed them up. The method chunker never shows `markReturned` for this question.
   - Lines @ k=8, Q2: says `Catalog`'s *constructor* initialises the map. **There is no constructor.** The whole-file chunk gave it room to fill in boilerplate.
   - Lines @ k=8, Q3: garbles `isOverdue` ("checks if the loan's return date is after the standard term").
3. **Retrieval miss on the smallest target**: at k=2 the line chunker misses Q6. `Member.java:1-50` is one big window that is about everything a member does, so it ranks below the Loan and Book windows for "how many books can a member have on loan". The 9-line `Member.addLoan` method chunk ranks **#1** for the same query.

**Takeaway:** chunking quality shows up less in "was the right text somewhere in the context" and more in **how much irrelevant text comes with it**. That drives token cost, latency and the model's chance of building an answer out of neighbouring code. A larger corpus would also show the expected hit-rate drop.

---

### 6. "Question not in corpus" experiment (task 8)

Added question 11: *"How does the Spring Boot REST controller authenticate users with JWT tokens?"* The repo has no Spring, REST, auth or JWT.

| Prompt | Answer to Q11 |
|---|---|
| **With** the two guard lines (`prompts/rag.txt` lines 2–3) | **"I do not find that in the passages."** ✅ (same in every run: method or lines chunker, k = 2 or 8) |
| **Without** them (`runs/rag-no-guard.txt`, [`run5`](m6-pub/runs/run5-no-guard.txt)) | *"The Spring Boot REST controller authenticates users with JWT tokens by verifying the token's signature and payload. The `@AuthenticationPrincipal` annotation is used to extract the user's details … the `@Secured` annotation is used to restrict access … The `JwtTokenUtil` class is used to verify the token's signature, as shown in the `verifyToken` method."* ❌ **Entirely invented.** No such class, method or annotation exists in the repo, and it even says "as shown in", pointing at code that isn't there. |

Removing the guard also introduced smaller inventions in **in-corpus** answers in the same run. For example, Q2 claims `searchByTitle` has *"a limit of 1 result per search"* and calls a non-existent `searchByIsbn`. The guard doesn't just handle off-topic questions; it keeps the model anchored to the passages on every question.

The guard lines were **restored** afterwards (`prompts/rag.txt` matches `runs/rag-final.txt`).

---

### 7. Deviations and environment notes

- **Model**: `llama3.2:3b` instead of `llama3.1:8b` (8 GB RAM). You can override it with `OLLAMA_MODEL=llama3.1:8b make run`.
- **Makefile**: on Windows the Java classpath separator is `;`, not `:`, so the starter Makefile couldn't compile anything. I added an `ifeq ($(OS),Windows_NT)` separator switch, quoted `-cp`, and added an `eval` target. The original is kept at `disabled/Makefile.orig`.
- **Maven**: not installed system-wide. `make langchain4j` was run with Apache Maven 3.9.9 unpacked to `~/tools` (SHA-512 verified).
- **`OllamaLlm`**: copied from `example/` into `src/` and given a 5-minute timeout, a configurable model name and a `toString()` for the run log. The earlier pre-langchain4j copy is in `disabled/`.
- **`rag.txt`** was edited (see section 3). The two guard lines from task 8 are lines 2–3.
- **Commit:** committed on branch `session-6a` (`6e70393`) and merged into `main` through pull request #1.

---

## Part B: Session 6B, agentic mini-pipeline

**Setup.** `llama3.2:3b` via Ollama (temperature 0, max 300 output tokens) on an 8 GB Windows laptop. The catalogue is `books.json`: 30 books, about 3.3 KB, which is **~840 tokens** if `list_books` returns all of it. Token counts throughout are estimates (characters ÷ 4). Every number below comes from a trace in [`traces/`](m6-pub-b/traces/); see [`traces/README.md`](m6-pub-b/traces/README.md) for the index. All runs are reproducible with `./run-experiments.sh task4|task5|task6`.

Question A has exactly one correct answer: **1** (*The Dispossessed*, 1974). Le Guin's other two books in the catalogue are from 1968 and 1969, so a model that guesses or misreads the decade gets 3.

---

### What I built

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

### Task 3: question A with sharp tool descriptions

Trace: [`traces/A-sharp.log`](m6-pub-b/traces/A-sharp.log)

| | |
|---|---|
| Tool called | `search_books` only |
| Arguments | `"Ursula Le Guin\|1970\|1979"`, exactly the intended filter |
| Iterations | 3 LLM calls: search → repeated the same search (blocked by the loop guard) → `Final Answer: 1` |
| Answer | **1, correct** |
| Tokens | about 1,350 |

Getting here took four iterations of the driver and prompt (traces `A0`–`A3`). The first run, with the **unmodified starter prompt** ([`A0-starter-prompt.log`](m6-pub-b/traces/A0-starter-prompt.log)), picked the right tool with the right arguments on step 1. It then never answered: it called `list_books` once and repeated the same search until the 6-step budget ended it, after about 5,900 tokens and no answer. The step budget was the only reason that run ended.

---

### Task 4: vague tool descriptions

Each variant changes only the tool-description block of `prompts/react.txt`:

- **v1** ([`react-vague1.txt`](m6-pub-b/prompts/react-vague1.txt)): the handout's change. `search_books` becomes "Search for books." Its argument format and the `list_books` cost warning stay.
- **v2** ([`react-vague2.txt`](m6-pub-b/prompts/react-vague2.txt)): both descriptions vague ("Get books." / "Search for books."). All cost hints and the empty-part example are removed; only the bare `author|year_from|year_to` line remains.
- **v3** ([`react-vague3.txt`](m6-pub-b/prompts/react-vague3.txt)): like v2, but the argument format is removed as well. Only the tool names are left.

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

### Task 5: step budget of 3 on a harder question

Question H: *"Compare Ursula K. Le Guin, Octavia E. Butler and Neal Stephenson: for each author, in which decade did they publish the most books?"* The minimum is 3 searches (`Le Guin||`, `Butler||`, `Stephenson||`) plus a final answer, so 4 LLM calls, more than a budget of 3 allows.

| Budget | Trace | Result |
|---|---|---|
| `--steps 3` | [`H-steps3.log`](m6-pub-b/traces/H-steps3.log) | `list_books` → `search(Le Guin\|1970\|1979)` → about to `search(Butler\|1970\|1979)` → **`ABORT: step budget (3) exhausted before a final answer`**. Stop reason `STEP_BUDGET`, 2 tool calls, 3 LLM calls, final answer `null`. ✅ Clean abort, no exception. The 3rd Action was parsed but **not executed**. |
| `--steps 10` (control) | [`H-steps10.log`](m6-pub-b/traces/H-steps10.log) | Ran 6 of 10 steps and ended with `FINAL_ANSWER`, but the answer was *"The previous Observation contains the entire catalogue."* ❌ The model guessed a decade for each author (`Le Guin\|1970\|1979`, `Butler\|1970\|1979`, `Stephenson\|1990\|1999`) instead of querying each author without dates. It repeated `list_books`, the loop guard told it to answer now, and the forced answer was empty. |

The correct answer is Le Guin **1960s** (2 books, vs 1 in the 1970s), Butler a **tie** between the 1970s and 1990s (1 each), Stephenson **1990s** (2). The budget behaved exactly as intended. Planning a multi-author comparison is beyond this 3B model regardless of budget.

---

### Task 6: token budget

| Run | Trace | Result |
|---|---|---|
| A, `--tokens 800` | [`A-tokens800.log`](m6-pub-b/traces/A-tokens800.log) | Search ran (346 tokens used). The next call needed a prompt of about 384 tokens plus the 300 reserve, which would exceed 800, so it **fired before step 2**. `TOKEN_BUDGET`, 346/800 tokens used. ✅ |
| A, `--tokens 2000` | [`A-tokens2000.log`](m6-pub-b/traces/A-tokens2000.log) | This time the model went greedy: search, then **`list_books`** (+838 tokens), and the next prompt (~1,275) plus the reserve didn't fit. **Fired**: `TOKEN_BUDGET`, 808/2000. ✅ |
| A, `--tokens 2000`, repeated | [`run2`](m6-pub-b/traces/A-tokens2000-run2.log), [`run3`](m6-pub-b/traces/A-tokens2000-run3.log) | Search only, then `Final Answer: 1` at **~1,335/2000**. ✅ Did not fire. |
| C on v2, `--tokens 2000` | [`C-tokens2000-vague2.log`](m6-pub-b/traces/C-tokens2000-vague2.log) | Bad search, then `list_books` (+838), and the next call wouldn't fit. **Fired**: 585/2000. ✅ |

So it fires when expected. With the same question and the same budget, the budget is never hit on the cheap path and always hit once the agent pulls in the whole catalogue. For this catalogue, a budget of about 1.5–2k tokens effectively bans `list_books`, which is a useful way to enforce the "prefer the cheap tool" policy from outside the model.

**A bug the experiment caught.** My first token-budget version only checked the *prompt* before each call. The first task-6 run ended at **~820/800**, over budget, because the reply is counted after the call. Reserving the maximum reply size (300 tokens, the same as the model's output cap) fixed it, and a test now checks the budget is never exceeded.

---

### Driver fixes the runs forced on me

Each of these came from a specific failed trace; old traces are kept under `traces/superseded/`:

1. **Discard model-written Observations.** The 3B model regularly continued past its Action and wrote its own `Observation:`, often with made-up results, plus a `Final Answer`. The driver cuts each reply at the first `Observation:` line. The traces record how much was dropped, up to ~770 characters per step.
2. **Put a count line at the top of `search_books` output** ("1 matching book:"). Without it, the model looked at a one-element JSON array and decided *"the filter must be too narrow"*, then kept widening the year range (`A1-sharp-no-count.log`).
3. **Loop guard.** An identical repeated tool call is not re-run. The observation instead says "REPEATED CALL … reply now with Final Answer". This turned A from never-finishing (`A2-sharp-no-loop-guard.log`) into a correct 3-step run.
4. **Accept a Final Answer written after a *repeated* call, but not after a *new* one.** The model often writes `Action: <same call again>` and then the correct `Final Answer` in the same reply. The repeat can't add information, so that answer only uses real observations and is accepted. After a *new* call, the answer was written before seeing the result, so it's a guess and is ignored (both cases tested).
5. **Parser bug.** `Action Input:\s*(.*)` let `\s*` run past a line break, so an empty input captured the next line, which here was the `Final Answer`, as the tool argument. Fixed by using `[ \t]*`, with a regression test.
6. **Output cap and LLM-error stop.** Ollama has no output limit by default. One generation ran for 5 minutes until the HTTP timeout and crashed the run. Now: `numPredict(300)`, a 2-minute timeout, and a clean `LLM_ERROR` stop reason.

---

### Reflection

#### Which tool did the agent prefer, and why?

**For its first call, `search_books`, whenever the prompt described its argument format and the question was a single filter** (6 of 6 task-4 runs with sharp, v1 and v2; every A and C run with the sharp prompt). The exception is H, which needs data on three authors: both H runs opened with `list_books`. That is arguably a sensible choice for a question that isn't a single filter, and it's exactly what the description allows ("use only if the query cannot be expressed as a filter"). I think the format line, not the cost warning, is what did it. v1 removed "returns only matching books" and changed nothing; v3 removed the format and the first choice flipped to `list_books` every time. A question like "Le Guin in the 1970s" maps almost directly onto `author|year_from|year_to`. A small model mostly fills templates, and the format line is a template to fill.

**But it used `list_books` at some point in 6 of 8 task-4 runs**, usually *after* a correct search, as reassurance. Its Thoughts give reasons such as "verify there are no other books" or "the filter must be too narrow", and they read like rationalisations rather than reasoning. In `A2` the Thought at step 3 correctly names *The Dispossessed* (1974), and the very next action is `list_books` anyway. That's the handout's pitfall: **the Thought describes what happened; it isn't evidence the action was right.**

#### How much did tool-description quality matter?

A lot, but in a specific way:

- **The argument format mattered most.** Without it the model invented syntax (`author="…" AND year=1970`) and passed arguments to a tool that ignores them.
- **Examples mattered.** Removing the `"|1990|2000"` example (v2) was enough for the model to produce `year_from=1990|year_to=1999` and fail.
- **The description text itself ("Search for books." vs "returns only matching books") mattered little** for these two questions.
- Good tool *output* helped as much as good descriptions: the count line and an ERROR that restates the format. The model reads the tool's output much more literally than its description.

Correctness fell from 2/2 (sharp, v1) to 1/2 (v2, v3), and the failures were *confident wrong answers* ("1990-1999") or requests for information the user had already given. Nothing crashed.

#### What would a production driver need on top of this?

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

### Deviations and notes

- **Model:** `llama3.2:3b`, not `llama3.1:8b`, because the machine has 8 GB of RAM. Override with `OLLAMA_MODEL`.
- **Prompt:** [`prompts/react.txt`](m6-pub-b/prompts/react.txt) is my revised version. It gives the two output formats more explicitly and adds three rules (don't write Observations, don't repeat Actions, answer as soon as you can). The original is kept unchanged as [`react-starter.txt`](m6-pub-b/prompts/react-starter.txt). The tool-description block is identical apart from the task-4 variants.
- **Construction sites:** the handout names two places to switch from `new StubLlmClient()` to `new OllamaLlm()`.
  1. **The test site.** The seed `test/AgentDriverTest.java` is unchanged, because it checks for the stub's `"STUB RESPONSE"` text and a literal swap would break it. Instead, `test/AgentDriverLiveTest.java` repeats its tests with `new OllamaLlm()`, and the live question-A test skips itself when Ollama isn't running.
  2. **The `Main` site.** The new `src/Main.java` uses `new OllamaLlm()` by default (`--stub` switches back).
- **Makefile:**
  - Same Windows classpath-separator fix as in 6A, plus a `run` target (`make run ARGS='--steps 3 "question"'`).
  - **Test classpath fix:** the starter's `test` target ran `java -cp "$(CP)" -jar junit.jar --class-path build`. `java -jar` *ignores* `-cp`, so the langchain4j jars never reached the tests. Any test that constructs `OllamaLlm` failed with `NoClassDefFoundError`. The full classpath now goes to JUnit's own `--class-path`.
- **Build requirement:** because `src/OllamaLlm.java` is part of the build, `make build` and `make test` need `make langchain4j` to have been run once, as in 6A.
- **Not used:** `solution/`. Everything here is my own implementation.
