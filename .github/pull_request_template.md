## Change

<!-- Describe the problem and resulting behavior. Include material risks. -->

## Validation

- [ ] `./gradlew verify`
- [ ] `./gradlew :app:assembleMobileProductionDebug :app:assembleTvProductionDebug`
- [ ] Behavior exercised on the emulator (`scripts/prove.sh <mobile|tv>` or a feature lane)
- [ ] Visual proof attached to this PR

<!-- Record the app and SDK SHAs and `git status --short` for both worktrees
used for local proof. Attach reviewed screenshots or recordings from
`.evidence/` with `gh pr create --attach` or `gh pr comment <n> --attach`;
never commit them. Name skipped checks and unresolved failures. Remove this
comment when writing the description. -->
