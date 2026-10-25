package dev.aiverify.gates;

import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.AuthorizationPrecheck;
import dev.aiverify.pipeline.Gate;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.RunContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gate 2 - Compile in memory and check the code honours the human-owned contract. */
public final class CompileGate implements Gate {

    @Override
    public String id() {
        return "G2";
    }

    @Override
    public String name() {
        return "Compile & contract";
    }

    @Override
    public String decidedBy() {
        return "deterministic";
    }

    @Override
    public GateResult run(RunContext ctx) {
        long t0 = System.currentTimeMillis();
        InMemoryCompiler.Result r = new InMemoryCompiler().compile(RunContext.CANDIDATE_CLASS, ctx.candidateSource);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("compilerErrors", r.errors());
        if (!r.ok()) {
            return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                    "Does not compile (" + r.errors().size() + " error(s)).", details, System.currentTimeMillis() - t0);
        }
        if (!AuthorizationPrecheck.class.isAssignableFrom(r.loadedClass())) {
            details.put("compilerErrors", List.of("Class does not implement " + AuthorizationPrecheck.class.getName()));
            return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                    "Compiles, but does not implement the contract.", details, System.currentTimeMillis() - t0);
        }
        ctx.compiledClass = r.loadedClass();
        try {
            ctx.newCandidate();
        } catch (RuntimeException e) {
            details.put("compilerErrors", List.of(e.getMessage()));
            return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                    "Compiles, but cannot be created with a no-argument constructor.", details,
                    System.currentTimeMillis() - t0);
        }
        return new GateResult(id(), name(), GateResult.Status.PASSED, decidedBy(),
                "Compiles with Java 21 and implements AuthorizationPrecheck.", details, System.currentTimeMillis() - t0);
    }
}
