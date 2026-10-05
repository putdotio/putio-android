## Change

<!-- One sentence on the outcome, then one visual aid: a screenshot or short
recording for UI, a Mermaid diagram for a flow, a table for numbers, or a short
code sample for an API. Add one-line bullets only for risks the aid doesn't
show. No test counts, command logs, file lists, or review history. -->

## Validation

- [ ] Code changes: `./gradlew verify` and `./gradlew :mobile:assembleProductionDebug :tv:assembleProductionDebug`
- [ ] Proof for each matching row of the AGENTS.md proof map

<!-- Docs-only changes need no build; run `./gradlew markdownCheck`. When local
proof used `putioSdkKotlinPath`, record the app and SDK short SHAs. Attach
reviewed captures from `.evidence/` with `gh pr create --attach`; never commit
them. End with one "Unverified:" line for skipped or unavailable proof. Remove
this comment when writing the description. -->
