# Replay answers for the wealth scenario (offline demo)

These files are **sample model answers** written for the offline demo of the wealth-management scenario
(fund subscription suitability pre-check), so the pipeline runs anywhere without an API key. They are not
recordings of a live model. Defects were planted on purpose, like fake notes fed to a banknote checker.
Every gate that judges them is real and runs exactly as in live mode.

| File | Role | What the sample shows |
| --- | --- | --- |
| `test-writer.txt` | Test writer (sees the spec only) | 15 reasonable tests that cover every criterion but miss the exact boundaries |
| `attempt-1/author.txt` | Author | Two boundary bugs: KYC anniversary (AC-02) and equal risk level (AC-03) |
| `attempt-1/diagnostician.txt` | Diagnostician | Root cause, plus spec-level feedback that does not leak hidden test data |
| `attempt-2/author.txt` | Author, after feedback | Boundaries fixed, but parses levels with `Integer.parseInt` (accepts `C03`, `RR+2`) |
| `attempt-2/reviewer.txt` | Independent reviewer | HIGH finding on `Integer.parseInt`, confirmed by golden tests G-32 and G-33 |
| `attempt-3/author.txt` | Author, after human rejection | Exact match against the allowed codes |
| `attempt-3/reviewer.txt` | Independent reviewer | One LOW note (theoretical overflow) |

Run with `./run.sh wealth run --attempt N --live` to replace every sample with a real Claude call.
