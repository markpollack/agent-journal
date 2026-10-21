# Negative control: a rules document the reader silently shrinks

Three rule headings; only one is well-formed. `RequirementsRosterTest` asserts that the reader returns
fewer identifiers than the headings declare and that the roster comparison names each skipped one.

### RULE-1
**MUST** keep this rule well-formed.
**Reason:** It is the control that parses.

### RULE-2
**MUST** be skipped because this rule has no reason line.

### RULE-3
MUST be skipped because the keyword is not bold, so the requirement line never matches.
**Reason:** A reason without a matched requirement is ignored.
