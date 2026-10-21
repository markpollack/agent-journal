# java-guardrails decisions

Owner decisions for the kit's gates on this project (`markpollack/java-guardrails` v0.1.0, installed as the
Claude Code plugin `java-guardrails@java-guardrails`). One line per decision; the scripts read the `KEY: value`
lines and do not re-ask. Changing a decision is an edit here.

GATE_ORDER: jacoco, spotbugs, errorprone, pmd, archunit; pit scoped-only (2026-10-21)

Why this order instead of the kit's default (PMD first): the kit's default order is tiered by how proven each gate
is; this project's immediate need is ordered by what its own evidence showed on 2026-10-21 (ACCEPT-1 follow-up).
PIT already exists here in two module profiles and reported that the launching capture's option handling had never
been executed by any test (34 uncovered mutants) and that 247 covered mutants survived; the never-executed part is a
line-coverage fact that JaCoCo reports in seconds, so coverage floors come first. The new launcher code is a reader
thread with a lock, a latch and volatile fields, which is SpotBugs' territory (its findings on the MCP Java SDK were
all concurrency or locking), so SpotBugs is second. Error Prone third (needs the JDK 21 compiler; CI builds on 21).
PMD fourth: the long launcher methods are the refactor-to-testability target once the detectors above are green.
ArchUnit last. PIT stays as a profile over named classes, run on the classes a change touched during work and whole
module only at acceptance returns; it is not a per-commit gate.
