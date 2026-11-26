# Google Play delivery

[Google Play Internal](../.github/workflows/play-internal.yml) uploads both
bundles; [Google Play Release](../.github/workflows/play-release.yml) promotes or
changes the rollout. Their inputs own the options.
[Google Play Data Safety](../.github/workflows/play-data-safety.yml) replaces the
listing's Data safety answers with `play/data-safety.csv`. Google can't read
them back, so a change to what the apps collect edits that file in the same
pull request.

- Every dispatch, `dry_run` included, needs Altay's OK: it loads the `release`
  Environment's secrets.
- If an upload lands but its `tag` job fails, rerun only that job. A full
  rerun repeats the version code, and Google Play refuses it.
- Each upload needs a new `version_name`. Mobile and TV share the
  [putio-tv-native-legacy](https://github.com/putdotio/putio-tv-native-legacy)
  listing's version codes.
- Google Play can't downgrade installs. Halt a bad rollout with the `halt`
  input of Google Play Release, then ship the fix under a new `version_name`.

## Proof

A delivery change builds both bundles, then runs a `dry_run` dispatch:

```bash
./gradlew -PputioVersionName=3.0.1 :mobile:bundleProductionRelease :tv:bundleProductionRelease
```
