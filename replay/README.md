# Replay answers (offline demo)

These files are **sample model answers** written for the offline demo, so the pipeline runs
anywhere without an API key. They are not recordings of a live model. Every gate that judges
them is real and runs exactly as in live mode.

| File | Role | What the sample shows |
| --- | --- | --- |
| `test-writer.txt` | Test writer (sees the spec only) | 15 reasonable tests that cover every criterion but miss the exact boundaries |
| `attempt-1/author.txt` | Author | Two boundary bugs: expiry month (AC-03) and equal balance (AC-05) |
| `attempt-1/diagnostician.txt` | Diagnostician | Root cause, plus spec-level feedback that does not leak hidden test data |
| `attempt-2/author.txt` | Author, after feedback | Boundaries fixed, but uses `Character.isDigit` (accepts full-width digits) |
| `attempt-2/reviewer.txt` | Independent reviewer | HIGH finding on `Character.isDigit`, confirmed by golden tests G-32 and G-33 |
| `attempt-3/author.txt` | Author, after human rejection | ASCII-only digit check |
| `attempt-3/reviewer.txt` | Independent reviewer | Only a MEDIUM spec gap (null inputs) |

Run with `--live` to replace every sample with a real Claude call.
