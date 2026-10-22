#!/usr/bin/env bash
# Session 6B experiments: runs the agent against Ollama and saves one trace
# per run under traces/ plus a one-block summary per run in traces/SUMMARY-<set>.txt.
#
#   ./run-experiments.sh task4   # question A + C across sharp and vague prompts
#   ./run-experiments.sh task5   # step budget = 3 on a multi-call question
#   ./run-experiments.sh task6   # token budget
#
# Each run is capped at 4 minutes of wall time so one stuck generation
# cannot stall the whole batch.
set -u
cd "$(dirname "$0")"
make build >/dev/null 2>&1 || { echo "build failed"; exit 1; }

case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=';' ;; *) SEP=':' ;; esac
CP="libs/junit.jar${SEP}$(find libs/langchain4j -name '*.jar' | paste -sd "$SEP" -)${SEP}build"

QA="How many books did Ursula Le Guin write in the 1970s?"
QC="Which books were published between 1990 and 1999?"
QH="Compare Ursula K. Le Guin, Octavia E. Butler and Neal Stephenson: for each author, in which decade did they publish the most books?"

SET="${1:?usage: $0 task4|task5|task6}"
SUMMARY="traces/SUMMARY-$SET.txt"
mkdir -p traces
: > "$SUMMARY"

run() {   # run <trace-name> <args...>
    local name="$1"; shift
    echo "######## $name" | tee -a "$SUMMARY"
    timeout 240 java -cp "$CP" Main --trace "traces/$name.log" "$@" 2>&1 \
        | grep -v "SLF4J" | tee -a "$SUMMARY"
    [ "${PIPESTATUS[0]}" = 124 ] && echo "(killed after 240 s)" | tee -a "$SUMMARY"
    echo | tee -a "$SUMMARY"
}

case "$SET" in
  task4)
    for v in sharp vague1 vague2 vague3; do
      p=prompts/react.txt; [ "$v" != sharp ] && p="prompts/react-$v.txt"
      run "A-$v" --prompt "$p" "$QA"
      run "C-$v" --prompt "$p" "$QC"
    done ;;
  task5)
    run "H-steps3"  --steps 3 "$QH"
    run "H-steps10" --steps 10 "$QH" ;;
  task6)
    run "A-tokens800"  --tokens 800  "$QA"
    run "A-tokens2000" --tokens 2000 "$QA"
    run "C-tokens2000-vague2" --tokens 2000 --prompt prompts/react-vague2.txt "$QC" ;;
  *) echo "unknown set $SET"; exit 2 ;;
esac
