# Applicability control: one conditional rule beside one unconditional rule

`RequirementsRosterTest` asserts that the reader loads an explicit `Applies when:` entry as the rule's
applicability (which is what makes NOT_APPLICABLE an available answer) and that a rule without one is
unconditional. The agent-journal documents declare no applicability clause anywhere.

### RULE-1
**MUST** apply only where the stated condition holds.
**Reason:** The condition is explicit, so an audit may answer not applicable with a reason.
**Applies when:** the CLI offers a native session file and no stream.

### RULE-2
**MUST** apply to every implementation.
**Reason:** No condition is stated, so no audit may exclude it.
