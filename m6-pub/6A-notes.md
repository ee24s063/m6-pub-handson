# Session 6A — RAG over a local Java repo: notes

**Setup used for every LLM run below**

| Component | Choice |
|---|---|
| Embedder | `all-MiniLM-L6-v2` (384-dim, in-process ONNX via langchain4j 0.36.0) — `MiniLmEmbedder` |
| LLM | `llama3.2:3b` via Ollama, temperature 0 — `OllamaLlm` (8 GB RAM machine, so the 3B model rather than the 8B in the handout) |
| Corpus | `sample-repo/` — 8 files, 9 types (incl. package-private `StandardFinePolicy`), 43 methods/constructors, ~400 lines |
| Retrieval | cosine similarity over an in-memory list, top-k = 8 unless stated |
| Hardware | Windows 11 laptop, CPU inference |

Raw output for every run is in [`runs/`](runs/); the exact prompt used for each experiment is saved next to it (`runs/rag-*.txt`).

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

## 1. What was implemented

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

## 2. Indexer stats (task 3)

| Chunker | Embedder | Chunks | Index time |
|---|---|---|---|
| Starter stub (one chunk per file) | Stub (32-dim hash) | **8** | 15–18 ms |
| Method chunker | Stub | **52** | ~29 ms |
| Method chunker | MiniLM | **52** | 1378 ms cold (first run), 620–870 ms warm |
| 50-line chunker (task 7) | MiniLM | 12 | ~245 ms |

The 52 chunks are 43 method/constructor chunks plus 9 type-header chunks. That's 6.5× the per-file count, which is consistent with the ~5 methods per class in this repo, so the chunker really is splitting on methods and not merging them. The MiniLM indexing time is almost all ONNX model load and inference. The chunking itself takes under 30 ms.

---

## 3. Ten questions, answers, judgement (task 5)

Run: [`runs/run1-method-chunker.txt`](runs/run1-method-chunker.txt). Method chunker, k = 8, final prompt. Each answer took 1.6–3.0 s once the model was warm.

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

### Prompt iterations: an extra finding

The anti-hallucination wording mattered more than I expected with a 3B model. I went through three versions of `prompts/rag.txt`, all with the same retrieval:

| Prompt version | File | Result |
|---|---|---|
| v1: question *before* context, "reply **exactly**: I do not find that in the passages." | `runs/rag-strict.txt`, [`run0`](runs/run0-method-strict-prompt.txt) | **Refused 6 of 10** in-corpus questions (Q1, Q3, Q5, Q6, Q7, Q10), even though the right chunk was in the top 8 every time, at rank 1 for five of the six. Over-strict guard means false refusals. |
| v2: context *before* question, softer guard, "name the class or method" | `runs/rag-terse.txt`, [`run0b`](runs/run0b-method-terse-prompt.txt) | 1 refusal (Q6). Q4 answered just "CAP" and Q7 just "DEFAULT_TERM_DAYS": names, not values. |
| v3 (final): v2 + "1–3 sentences, state concrete values … and name the method" | `prompts/rag.txt` | Results in the table above |

The lesson is that the guard works in both directions. The same instruction that stops fabrication (see section 6) will, if phrased too absolutely, make a small model refuse when it has the answer in front of it.

---

## 4. Retrieval hit-rate (task 6)

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

## 5. "Bad chunker" experiment (task 7)

`CHUNKER=lines`: 50-line windows, giving 12 chunks of 34 lines on average. Runs: [`run2`](runs/run2-lines-chunker.txt) (k=8), [`run4`](runs/run4-lines-chunker-k2.txt) (k=2). The method chunker at k=2 is [`run3`](runs/run3-method-chunker-k2.txt).

### Result

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

### Honest reading: the hit-rate barely moved, and here is why

The handout expects the hit-rate to drop. **On this corpus it didn't.** The line-coverage hit-rate stayed at 1.00@8, and MRR actually went up. That's a property of the corpus, not evidence that 50-line chunks are good:

- **Every file is ≤ 66 lines**, so a 50-line window is basically a whole file. That's 12 chunks for 8 files.
- **At k=8 you retrieve 8 of 12 chunks, two-thirds of the entire repository.** Covering the right line is close to guaranteed. This is exactly the "crank k up to compensate for a bad chunker" pitfall: it scores well because it sends nearly everything.
- A whole-file chunk is an easy match for "which *class* does X" questions. On a large corpus (Assignment 11), files are hundreds of lines and k=8 is a tiny fraction, so a window would mix several unrelated methods and its embedding would match none of them well.

### Where the bad chunker *does* hurt, even here

1. **Cost and latency**: 2.8× the prompt tokens and about 6× the answer time at the same k. This is the "good top-4 beats bad top-16" point from the pitfall box. The method chunker's top-4 (~444 tokens) already reaches 0.90 hit-rate.
2. **Unrelated code in the context produces hallucinations**:
   - Lines @ k=2, Q5: *"The `markReturned` method also writes to the loan registry by updating the `returnedOn` field"*. **Wrong.** The `Loan.java` window was retrieved alongside `LoanRegistry`, and the model mixed them up. The method chunker never shows `markReturned` for this question.
   - Lines @ k=8, Q2: says `Catalog`'s *constructor* initialises the map. **There is no constructor.** The whole-file chunk gave it room to fill in boilerplate.
   - Lines @ k=8, Q3: garbles `isOverdue` ("checks if the loan's return date is after the standard term").
3. **Retrieval miss on the smallest target**: at k=2 the line chunker misses Q6. `Member.java:1-50` is one big window that is about everything a member does, so it ranks below the Loan and Book windows for "how many books can a member have on loan". The 9-line `Member.addLoan` method chunk ranks **#1** for the same query.

**Takeaway:** chunking quality shows up less in "was the right text somewhere in the context" and more in **how much irrelevant text comes with it**. That drives token cost, latency and the model's chance of building an answer out of neighbouring code. A larger corpus would also show the expected hit-rate drop.

---

## 6. "Question not in corpus" experiment (task 8)

Added question 11: *"How does the Spring Boot REST controller authenticate users with JWT tokens?"* The repo has no Spring, REST, auth or JWT.

| Prompt | Answer to Q11 |
|---|---|
| **With** the two guard lines (`prompts/rag.txt` lines 2–3) | **"I do not find that in the passages."** ✅ (same in every run: method or lines chunker, k = 2 or 8) |
| **Without** them (`runs/rag-no-guard.txt`, [`run5`](runs/run5-no-guard.txt)) | *"The Spring Boot REST controller authenticates users with JWT tokens by verifying the token's signature and payload. The `@AuthenticationPrincipal` annotation is used to extract the user's details … the `@Secured` annotation is used to restrict access … The `JwtTokenUtil` class is used to verify the token's signature, as shown in the `verifyToken` method."* ❌ **Entirely invented.** No such class, method or annotation exists in the repo, and it even says "as shown in", pointing at code that isn't there. |

Removing the guard also introduced smaller inventions in **in-corpus** answers in the same run. For example, Q2 claims `searchByTitle` has *"a limit of 1 result per search"* and calls a non-existent `searchByIsbn`. The guard doesn't just handle off-topic questions; it keeps the model anchored to the passages on every question.

The guard lines were **restored** afterwards (`prompts/rag.txt` matches `runs/rag-final.txt`).

---

## 7. Deviations and environment notes

- **Model**: `llama3.2:3b` instead of `llama3.1:8b` (8 GB RAM). You can override it with `OLLAMA_MODEL=llama3.1:8b make run`.
- **Makefile**: on Windows the Java classpath separator is `;`, not `:`, so the starter Makefile couldn't compile anything. I added an `ifeq ($(OS),Windows_NT)` separator switch, quoted `-cp`, and added an `eval` target. The original is kept at `disabled/Makefile.orig`.
- **Maven**: not installed system-wide. `make langchain4j` was run with Apache Maven 3.9.9 unpacked to `~/tools` (SHA-512 verified).
- **`OllamaLlm`**: copied from `example/` into `src/` and given a 5-minute timeout, a configurable model name and a `toString()` for the run log. The earlier pre-langchain4j copy is in `disabled/`.
- **`rag.txt`** was edited (see section 3). The two guard lines from task 8 are lines 2–3.
- **Commit:** committed on branch `session-6a` (`6e70393`) and merged into `main` through pull request #1.
