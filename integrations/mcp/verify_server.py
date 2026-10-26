"""
MCP server that lets a coding agent (Claude Code, SpecBlast's own agent, ...) run the
AI Code Verification Gate on a generated project.

The agent gets fast, structured feedback while it works. The tool only REPORTS the gate's
verdict: it cannot approve anything. Deployment still needs the gate to pass and a named human.

Run:   python verify_server.py            (stdio transport)
Needs: Python 3.10+, `pip install "mcp>=1.2,<2"`, JDK 17+ on PATH.
"""
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

from mcp.server.fastmcp import FastMCP

GATE_ROOT = Path(os.environ.get("AIVERIFY_GATE", Path(__file__).resolve().parents[2]))
RUN = GATE_ROOT / ("run.cmd" if os.name == "nt" else "run.sh")

mcp = FastMCP("ai-code-verification-gate")


@mcp.tool()
def verify_generated_project(project_path: str, label: str | None = None) -> dict:
    """Verify a generated Spring Boot + Kubernetes project before it is deployed.

    Runs Java security rules, the project's CodingStyle.md as a contract, Kubernetes
    manifest policy and delivery-pipeline checks. Returns the verdict and every finding
    with the file, line and suggested fix.

    BLOCKED means at least one ERROR: fix them and verify again. READY_FOR_HUMAN_APPROVAL
    means no errors; warnings must still be reviewed by a named human. This tool never
    approves a deployment.
    """
    project = Path(project_path).expanduser().resolve()
    if not project.is_dir():
        return {"error": f"Not a folder: {project}"}

    before = set((GATE_ROOT / "runs" / "projects").glob("*/result.json"))
    cmd = [str(RUN), "verify-project", str(project)]
    if label:
        cmd += ["--label", label]
    proc = subprocess.run(cmd, cwd=GATE_ROOT, capture_output=True, text=True, timeout=1800)

    new = sorted(set((GATE_ROOT / "runs" / "projects").glob("*/result.json")) - before)
    if not new:
        return {"error": "The gate did not produce a result.", "output": (proc.stdout + proc.stderr)[-2000:]}
    result = json.loads(new[-1].read_text(encoding="utf-8"))

    findings = [
        {k: f.get(k) for k in ("rule", "severity", "file", "line", "title", "fix")}
        for g in result["gates"]
        for f in g["findings"]
        if f["severity"] != "PASS"
    ]
    findings.sort(key=lambda f: 0 if f["severity"] == "ERROR" else 1)
    return {
        "verdict": result["verdict"],
        "errors": result["errors"],
        "warnings": result["warnings"],
        "gates": {g["id"] + " " + g["name"]: g["status"] for g in result["gates"]},
        "findings": findings,
        "contentSha256": result["contentSha256"],
        "report": str(GATE_ROOT / "runs" / "projects" / "report.html"),
        "note": "This tool reports the gate's verdict only. It cannot approve a deployment; "
                "a named human must review the warnings and approve.",
    }


if __name__ == "__main__":
    mcp.run()
