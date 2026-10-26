# SpecBlast writes the ISO 8583 component

`iso8583-precheck.txt` is the spec given to SpecBlast. It has the same rules as
`spec/authorization-precheck.md` and asks for the rules in one plain Java class,
`AuthorizationRules`, so the gate can test exactly that code. It contains **no test cases**:
the 36 golden tests stay held out from SpecBlast.

`project/` is SpecBlast's output, unchanged. `SOURCE.txt` names the repository and commit it came from.

```bash
./run.sh verify-specblast examples/specblast-iso8583/project
#   Step 1: P1-P7 on the whole project
#   Step 2: G1-G6 on AuthorizationRules (the gate adds a marked adapter to the contract)
./run.sh approve --by "Your Name" --run specblast/RUN_ID
./run.sh production --run specblast/RUN_ID      # real-looking transactions on the approved code
```

In the web console this is tab 04. The AI test suite (G3) is the same sample set used in the
three-attempt demo, written from the spec only. The independent review (G5) runs when
`ANTHROPIC_API_KEY` is set; otherwise it is reported as not run, never as passed.
