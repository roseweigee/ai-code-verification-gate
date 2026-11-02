# The whole chain · 一條龍

One change, from the PM's document to production and back:

```
PM documents ─► SpecForge ─► SpecBlast ─► Quality gate ─► named human ─► release ─► canary + AIOps
 (any format)   one locked    generates    P1–P7 project    G6 approval              │
                spec + golden the project  G1–G5 rules                               ▼
                tests         from it      (golden tests    ◄── new golden tests ── SpecForge incident
                   ▲                        from the spec)                          (next spec version)
                   └───────────────────────── evidence pack: one page, every file hashed ──┘
```

| Step | Tool | What it proves |
| --- | --- | --- |
| 1 | SpecForge `check` / `lock` / `export` | Every PM writes differently; the spec is one canonical form, locked by a named person (SHA-256). Exports `requirements.md` for SpecBlast and `spec.md` + `golden-tests.json` for the gate. |
| 2 | SpecBlast | Generates the Spring Boot project from `requirements.md`. `contract-iso8583.md` (owned by the gate) asks for the rules in one plain class so they can be tested alone. |
| 3 | Gate `verify-specblast --spec-file --golden-file` | P1–P7 on the whole project, G1–G5 on the rules, against the golden tests of *that* spec version. The run records which spec (title, version, who locked it, hash). |
| 4 | Gate `approve --by` | G6: a named human. Project findings they accept are named in the approval. |
| 5 | Gate `release` | The approval record carries the spec it was approved against. |
| 6 | Gate `serve-release` + `canary-check` | The same 600 transactions to the live version and the new one; AIOps names every changed answer and traces it to a rule. |
| 7 | SpecForge `incident` | Each changed answer the spec decides becomes a new example (a new golden test); anything the spec cannot decide becomes a question for the PM. Draft of the next version, to be locked again. |
| 8 | Gate `evidence --run` | One HTML page per change: spec → code → checks → approval → release → canary → feedback, with every file and its SHA-256. |

## Run it

```bash
# SpecForge next to this repository (../specforge). SPEC.json: a spec with no open questions.
SPEC_OWNER="Name" chain/run-chain.sh SPEC.json
#  → stops after the export: upload export/specblast/requirements.md to SpecBlast, unzip the project, then
SPEC_OWNER="Name" APPROVER="Name" chain/run-chain.sh SPEC.json PROJECT_DIR
```

Options: `DRILL=1` also runs the incident drill (the scenario's attempt 1, with seeded defects, as a canary,
to show the feedback loop); `ACCEPT_PROJECT_FINDINGS=1` goes on past P1–P7 errors and names them in the
approval; `ANTHROPIC_API_KEY` turns on G5 (independent AI review).

Everything runs in a copy under `chain-runs/` with `--no-push`: nothing is deployed and this repository is not
changed. A real release stays a separate, deliberate step: `./run.sh release --run RUN_ID`.

## First full run (2026-11-02)

Real SpecBlast output (Claude, SpecBlast's own prompt, no hand edits) from SpecForge's export:

- **P1–P7:** P5 blocked: no unit test for the service class (accepted by name in the approval). Warnings: mutable image tag, non-root not enforced, no Cloud Build file.
- **G3:** the golden tests passed (9/9), but a null input crashed the rules (NF-05). The gate added the null guard and ran every gate again.
- **G4:** 88.2% of mutants caught. **G5:** 3 findings, none HIGH (for example: an empty card number returns 30 instead of 14).
- **Canary:** PROMOTE. No answer changed against the live version, so nothing to feed back.
- **Drill (seeded defects):** ROLL BACK (crashes, 54 up from 1.5% to 4.3%). SpecForge turned the 9 wrong transactions into 9 new examples, v1.1 was locked, and the gate re-ran: the seeded code failed 11 of 15 golden tests (5 of 9 with v1.0). The SpecBlast code passed all 15.

The run also showed one bug in SpecBlast itself: the generated `Makefile` was written to `src/main/java/` instead of the project root.

---

## 中文摘要

PM 文件 → **SpecForge** 統一成一份鎖定的規格（誰鎖的、雜湊值）→ **SpecBlast** 依規格產生專案 → **品質閘門** 用「這一版規格」的黃金測試檢查（P1–P7 專案、G1–G5 規則）→ **具名的人** 核准 → 發布 → **金絲雀 + AIOps** 比對新舊版本對同一批交易的回應 → 有答案變了，**SpecForge** 把它變成新的範例（新的黃金測試）或 PM 要回答的問題，產生下一版規格草稿 → 閘門用新版黃金測試再跑。每一次變更產生一份 **上線證據包**：規格 → 程式 → 檢查 → 核准 → 發布 → 金絲雀 → 回饋，所有檔案都附 SHA-256。
