# Trace index

All traces: `llama3.2:3b` via Ollama, temperature 0. Each `.log` is written by `AgentDriver.withTrace(...)`: a header (model, prompt file, budgets, query), every step (prompt size, raw reply, parsed action, observation), and a summary (stop reason, calls, estimated tokens, final answer).

Questions:

- **A**: "How many books did Ursula Le Guin write in the 1970s?" (correct: 1)
- **C**: "Which books were published between 1990 and 1999?" (correct: 5 books)
- **H**: "Compare Ursula K. Le Guin, Octavia E. Butler and Neal Stephenson: for each author, in which decade did they publish the most books?"

## Task 3: how the driver and prompt evolved on question A

| Trace | Driver/prompt state | Outcome |
|---|---|---|
| `A0-starter-prompt.log` | Starter `react.txt`, no extra guards | Correct search at step 1, then `list_books` and repeats; `STEP_BUDGET`, no answer |
| `A1-sharp-no-count.log` | Revised prompt | Model kept "widening" the year range after a correct 1-result search; `STEP_BUDGET` |
| `A2-sharp-no-loop-guard.log` | + count line in `search_books` output | Knew the answer (wrote "Final Answer: 1" after an Action) but never stopped; `STEP_BUDGET` |
| `A3-sharp.log` | + loop guard | ✅ `FINAL_ANSWER: 1` in 3 calls |

## Tasks 4–6 (final driver; produced by `run-experiments.sh`)

| Set | Traces | Summary |
|---|---|---|
| task4 | `A-sharp`, `C-sharp`, `A-vague1..3`, `C-vague1..3` | `SUMMARY-task4.txt` |
| task5 | `H-steps3` (budget 3: clean abort), `H-steps10` (control) | `SUMMARY-task5.txt` |
| task6 | `A-tokens800`, `A-tokens2000`, `A-tokens2000-run2/3`, `C-tokens2000-vague2` | `SUMMARY-task6.txt` |

## `superseded/`

Runs made with earlier driver versions that had bugs. They're kept for transparency but aren't used in the reflection:

- `superseded/*.log`: an empty `Action Input:` captured the next line (the `\s*` bug), and there was no output cap (A-vague3 timed out after 5 minutes).
- `superseded/round2/`: before Final Answers written after a repeated call were accepted.
