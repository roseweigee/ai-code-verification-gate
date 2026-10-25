package dev.aiverify.pipeline;

/** One verification step. Gates run in order; a FAILED gate stops the run. */
public interface Gate {

    String id();

    String name();

    String decidedBy();

    GateResult run(RunContext ctx);
}
