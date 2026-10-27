---
name: android-delivery
description: Reproduce a putio-android CI failure locally, or pick a Google Play version name. Use when a CI run fails, or before dispatching Google Play Internal or Google Play Release.
---

# Android delivery

Every Google Play dispatch, `dry_run` included, needs Altay's OK first. See
[Google Play delivery](../../../AGENTS.md#google-play-delivery).

## Reproduce a CI failure

1. Read the app SHA from the run summary.
2. Check it out in a detached worktree with `sdk.dir` set and
   `putioSdkKotlinPath` unset.
3. Run the same command the job ran.

A pull-request run tests a temporary merge commit. If it is gone, merge the
recorded second parent into the first and resolve conflicts explicitly. Never
substitute a newer base or head.

## Version codes

- Mobile and TV share the
  [putio-tv-native-legacy](https://github.com/putdotio/putio-tv-native-legacy)
  listing and its version codes.
- Google Play never takes a code twice, so each upload needs a new
  `version_name`
  ([`putioVersionCode`](../../../build-logic/src/main/kotlin/PutioAndroidApplicationPlugin.kt)).
- Only an upload Google Play rejected may reuse its name.
