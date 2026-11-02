#!/usr/bin/env bash
# The whole chain for one change, end to end:
#
#   SpecForge (lock the spec, export)  ->  SpecBlast (generate the project)  ->  quality gate (P1-P7, G1-G5)
#   ->  a named human approves (G6)  ->  release  ->  canary + AIOps  ->  evidence pack
#   ->  incident fed back to SpecForge (next spec version)  ->  the gate re-runs with the new golden tests
#
# Usage:
#   chain/run-chain.sh SPEC.json [SPECBLAST_PROJECT_DIR]
#
#   SPEC.json               a SpecForge spec with no open questions (canonical.json after the PM answered)
#   SPECBLAST_PROJECT_DIR   the project SpecBlast generated from export/specblast/requirements.md (unzipped).
#                           Without it the chain stops after the export and says what to upload to SpecBlast.
#
# Environment:
#   SPEC_OWNER   who locks the spec (required)            APPROVER   who approves the code (required for G6)
#   SPECFORGE    SpecForge folder (default ../specforge)  DRILL=1    also run the incident drill (see below)
#   ACCEPT_PROJECT_FINDINGS=1   go on although P1-P7 found errors; the approval note names each one
#   ANTHROPIC_API_KEY           if set, G5 (independent AI review) runs; HIGH findings are shown to the approver
#
# Everything runs in a copy of this repository under chain-runs/, with --no-push: nothing is deployed and
# the repository is not changed. A real release is a separate, deliberate step (./run.sh release --run ...).
#
# The canary here runs both revisions locally (serve-release) and replays the same 600 transactions. With
# DRILL=1 a second canary uses the scenario's attempt 1 (seeded defects) to show the feedback loop: the AIOps
# result goes back into SpecForge, which turns every wrong answer into a new golden test.
set -euo pipefail
cd "$(dirname "$0")/.."
GATE_REPO=$(pwd)
SPEC=$(realpath "${1:?Usage: chain/run-chain.sh SPEC.json [SPECBLAST_PROJECT_DIR]}")
PROJECT=${2:+$(realpath "$2")}
SPECFORGE=$(realpath "${SPECFORGE:-../specforge}")
: "${SPEC_OWNER:?Set SPEC_OWNER: the person who locks the spec}"

W=$GATE_REPO/chain-runs/$(date +%Y%m%d-%H%M%S)
mkdir -p "$W"
LOG=$W/chain.log
say() { printf '\n\033[1m== %s\033[0m\n' "$*" | tee -a "$LOG"; }
sf() { "$SPECFORGE/run.sh" "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS | tee -a "$LOG"; }

# the gate works in its own copy, so release/ and runs/ in the repository are not touched
mkdir -p "$W/gate"
tar -C "$GATE_REPO" --exclude ./.git --exclude ./chain-runs --exclude ./runs --exclude ./build -cf - . | tar -C "$W/gate" -xf -
mkdir -p "$W/gate/build/classes"
javac -d "$W/gate/build/classes" --release 17 $(find "$W/gate/src/main/java" -name '*.java') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
gate() { (cd "$W/gate" && java -cp build/classes -Daiverify.root=. dev.aiverify.Main "$@") 2>&1 | grep -v JAVA_TOOL_OPTIONS | tee -a "$LOG"; }

say "1 · SpecForge: check, lock (by $SPEC_OWNER), export"
sf check "$SPEC" --out "$W/spec"
sf lock "$W/spec/canonical.json" --by "$SPEC_OWNER" --version "${SPEC_VERSION:-1.0}"
LOCKED=$W/spec/canonical.locked.json
sf export "$LOCKED" --contract "$GATE_REPO/chain/contract-iso8583.md" --out "$W/export"

if [ -z "$PROJECT" ]; then
  say "2 · SpecBlast: waiting for the generated project"
  echo "Upload $W/export/specblast/requirements.md (and openapi.yaml) to SpecBlast, unzip the result, then run:"
  echo "  SPEC_OWNER=\"$SPEC_OWNER\" APPROVER=\"...\" chain/run-chain.sh $SPEC PROJECT_DIR"
  exit 0
fi
say "2 · SpecBlast project: $PROJECT"
cp -r "$PROJECT" "$W/specblast-project"

say "3 · Quality gate: P1-P7 on the project, G1-G5 on its AuthorizationRules, against the locked spec"
gate verify-specblast "$W/specblast-project" --spec-file "$W/export/gate/spec.md" --golden-file "$W/export/gate/golden-tests.json"
RUN=specblast/$(ls "$W/gate/runs/specblast" | sort | tail -1)
VERDICT=$(python3 -c "import json;print(json.load(open('$W/gate/runs/$RUN/result.json'))['verdict'])")
echo "Gate verdict: $VERDICT" | tee -a "$LOG"
if [ "$VERDICT" != "READY_FOR_HUMAN_APPROVAL" ]; then
  gate evidence --run "$RUN"
  echo "Blocked by the gate. Evidence: $W/gate/runs/$RUN/evidence/evidence.html"
  exit 1
fi

PROJECT_RESULT="$(ls -d "$W"/gate/runs/projects/*/ | sort | tail -1)result.json"
PROJECT_FINDINGS=$(python3 -c "
import json; r = json.load(open('$PROJECT_RESULT'))
print('; '.join(f['rule'] + ' ' + f['title'] for g in r['gates'] for f in g['findings'] if f['severity'] == 'ERROR'))")
if [ -n "$PROJECT_FINDINGS" ] && [ "${ACCEPT_PROJECT_FINDINGS:-0}" != "1" ]; then
  gate evidence --run "$RUN" --project-result "$PROJECT_RESULT"
  echo "The project has blocking findings: $PROJECT_FINDINGS" | tee -a "$LOG"
  echo "Fix them in SpecBlast and run again, or accept them by name: ACCEPT_PROJECT_FINDINGS=1 (recorded in the approval)."
  exit 1
fi

if [ -z "${APPROVER:-}" ]; then
  say "4 · Waiting for a named human (G6)"
  echo "Read $W/gate/report/index.html, then approve: (cd $W/gate && ./run.sh approve --run $RUN --by \"Your Name\")"
  exit 0
fi
say "4 · G6: approved by $APPROVER"
NOTE="Chain run: checked against $(python3 -c "import json;d=json.load(open('$LOCKED'));print(d['title'],'v'+d['version'])")"
[ -n "$PROJECT_FINDINGS" ] && NOTE="$NOTE. Accepted project findings: $PROJECT_FINDINGS"
gate approve --run "$RUN" --by "$APPROVER" --note "$NOTE"

say "5 · Release (--no-push: written to the copy's release/iso8583, not deployed)"
cp -r "$W/gate/release/iso8583" "$W/stable"   # what is live now
gate release --run "$RUN" --no-push
gate release-check

free_port() { python3 -c "import socket;s=socket.socket();s.bind(('127.0.0.1',0));print(s.getsockname()[1])"; }
serve() { # $1 = folder, $2 = port, $3 = log; prints the pid
  (cd "$W/gate" && exec java -cp build/classes -Daiverify.root=. dev.aiverify.Main serve-release --path "$1" --port "$2") > "$3" 2>&1 &
  echo $!
}
canary() { # $1 = canary folder, $2 = output json
  local ps pc p1 p2 ok
  ps=$(free_port); p1=$(serve "$W/stable" "$ps" "$W/serve-stable.log")
  pc=$(free_port); p2=$(serve "$1" "$pc" "$W/serve-canary.log")
  for port in "$ps" "$pc"; do
    ok=0
    for _ in $(seq 90); do
      [ "$(curl -s -m 2 "http://127.0.0.1:$port/healthz")" = ok ] && { ok=1; break; }
      sleep 1
    done
    if [ "$ok" != 1 ]; then
      echo "The service on port $port did not start:" | tee -a "$LOG"; cat "$W"/serve-*.log | tee -a "$LOG"
    fi
  done
  (cd "$W/gate" && java -cp build/classes -Daiverify.root=. dev.aiverify.Main canary-check --stable "http://127.0.0.1:$ps" \
     --canary "http://127.0.0.1:$pc" --n 600 --source "$1/AuthorizationPrecheckImpl.java" 2>&1 | grep -v JAVA_TOOL_OPTIONS) > "$W/canary.txt" || true
  kill "$p1" "$p2" 2>/dev/null || true
  wait "$p1" "$p2" 2>/dev/null || true
  grep -v '^CANARY_RESULT ' "$W/canary.txt" | tee -a "$LOG"
  grep '^CANARY_RESULT ' "$W/canary.txt" | head -1 | cut -d' ' -f2- > "$2"
  [ -s "$2" ] || { echo "No canary result (see above)." | tee -a "$LOG"; exit 1; }
}

say "6 · Canary: the same 600 transactions to the live version and to this release; AIOps compares them"
canary "$W/gate/release/iso8583" "$W/canary-result.json"
CHANGED=$(python3 -c "import json;print(json.load(open('$W/canary-result.json'))['incident'].get('changed',0))")

INCIDENT_OPT=()
if [ "$CHANGED" -gt 0 ]; then
  say "7 · Back to SpecForge: $CHANGED transactions changed answer in production"
  sf incident "$LOCKED" "$W/canary-result.json" --out "$W/incident"
  INCIDENT_OPT=(--incident "$W/incident/incident.md")
else
  say "7 · Back to SpecForge: no transaction changed its answer, nothing to feed back"
fi

say "8 · Evidence pack"
gate evidence --run "$RUN" --canary "$W/canary-result.json" "${INCIDENT_OPT[@]}" --project-result "$PROJECT_RESULT"
echo "Evidence: $W/gate/runs/$RUN/evidence/evidence.html" | tee -a "$LOG"

if [ "${DRILL:-0}" = "1" ]; then
  say "D1 · Incident drill: the scenario's attempt 1 (seeded defects) deployed as a canary"
  gate canary-prepare --attempt 1 --force >/dev/null
  mkdir -p "$W/drill" && cp "$W/gate/release/iso8583/AuthorizationPrecheckImpl.java" "$W/drill/"
  cp "$W/gate/release/iso8583/approval.json" "$W/drill/"
  # put the approved release back: the drill canary is compared with it
  gate release --run "$RUN" --no-push >/dev/null
  rm -rf "$W/stable" && cp -r "$W/gate/release/iso8583" "$W/stable"
  canary "$W/drill" "$W/drill/canary-result.json"

  say "D2 · SpecForge: every wrong answer becomes a new example (draft v1.1)"
  sf incident "$LOCKED" "$W/drill/canary-result.json" --version 1.1 --out "$W/drill/incident"
  sf lock "$W/drill/incident/canonical.json" --by "$SPEC_OWNER" --version 1.1
  sf export "$W/drill/incident/canonical.locked.json" --contract "$GATE_REPO/chain/contract-iso8583.md" --out "$W/drill/export"

  say "D3 · The gate re-runs attempt 1 with the v1.0 and the v1.1 golden tests"
  gate run --attempt 1 --spec-file "$W/export/gate/spec.md" --golden-file "$W/export/gate/golden-tests.json" || true
  gate run --attempt 1 --spec-file "$W/drill/export/gate/spec.md" --golden-file "$W/drill/export/gate/golden-tests.json" || true

  say "D4 · and the approved SpecBlast code with the v1.1 golden tests"
  gate verify-specblast "$W/specblast-project" --code-only --spec-file "$W/drill/export/gate/spec.md" \
    --golden-file "$W/drill/export/gate/golden-tests.json" || true
fi

say "Done: $W"
