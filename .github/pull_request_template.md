## Change

<!-- Describe the problem and resulting behavior. Include material risks. -->

## Validation

- [ ] Code changes: `./gradlew verify` and `./gradlew :mobile:assembleProductionDebug :tv:assembleProductionDebug`
- [ ] Proof for each matching row of the AGENTS.md proof map

<!-- Docs-only changes need no build; run `./gradlew markdownCheck` and check
the links and commands you touched. Emulator flows and attached captures apply
only to the rows that ask for them. When local proof used `putioSdkKotlinPath`,
record the app and SDK SHAs and `git status --short` for both worktrees. Attach
reviewed screenshots or recordings from `.evidence/` with `gh pr create --attach`
or `gh pr comment <n> --attach`; never commit them. Name skipped checks and
unresolved failures. Remove this comment when writing the description. -->
