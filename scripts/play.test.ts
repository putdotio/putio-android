import assert from "node:assert/strict";
import { test } from "node:test";

import { internalReleaseToPromote, productionReleases, rolloutRelease } from "./google-play.ts";
import { nextVersionCodes } from "./play.ts";

test("version codes clear every code Play has seen and the reserved one", () => {
  assert.deepEqual(nextVersionCodes([91], undefined), { mobile: 93, tv: 94 });
  assert.deepEqual(nextVersionCodes([91, 120, 95], undefined), { mobile: 121, tv: 122 });
  assert.deepEqual(nextVersionCodes([91], 200), { mobile: 200, tv: 201 });
  assert.throws(() => nextVersionCodes([91], 92), /must be above 92/);
  assert.throws(() => nextVersionCodes([91, 120], 120), /must be above 120/);
  assert.throws(() => nextVersionCodes([], undefined), /no version codes/);
});

const completed = { versionCodes: ["91"], status: "completed", name: "2.12.0" };
const draft = { versionCodes: ["90"], status: "draft" };
const staged = {
  versionCodes: ["93", "94"],
  status: "inProgress",
  userFraction: 0.1,
  countryTargeting: { countries: ["TR"] },
  name: "3.0.0",
};

test("a staged rollout keeps the completed release and drafts", () => {
  assert.deepEqual(productionReleases([completed, draft, staged], staged, staged, 50), [
    completed,
    draft,
    { ...staged, userFraction: 0.5 },
  ]);
});

test("a full rollout replaces the completed release and drops targeting", () => {
  assert.deepEqual(productionReleases([completed, draft, staged], staged, staged, 100), [
    draft,
    { versionCodes: ["93", "94"], status: "completed", name: "3.0.0" },
  ]);
});

test("a promoted release supersedes an older staged one", () => {
  const promoted = { versionCodes: ["95", "96"], name: "3.0.1" };
  assert.deepEqual(productionReleases([completed, staged], undefined, promoted, 10), [
    completed,
    { ...promoted, status: "inProgress", userFraction: 0.1 },
  ]);
});

test("promotion takes every code of the internal track's only release", () => {
  const internal = {
    versionCodes: ["93", "94"],
    status: "completed",
    name: "3.0.0",
    releaseNotes: [],
  };
  assert.deepEqual(internalReleaseToPromote([internal], 93), {
    versionCodes: ["93", "94"],
    name: "3.0.0",
    releaseNotes: [],
  });
  assert.throws(
    () => internalReleaseToPromote([internal], 95),
    /must be in the internal track's only release/,
  );
  assert.throws(
    () => internalReleaseToPromote([internal, { versionCodes: ["95"] }], 93),
    /holds \[93, 94\] \[95\]/,
  );
  assert.throws(() => internalReleaseToPromote([], 93), /holds nothing/);
});

test("only a staged, halted or draft production release's rollout changes", () => {
  assert.equal(rolloutRelease([completed, staged], 94), staged);
  assert.throws(() => rolloutRelease([completed, staged], 91), /completed production release/);
  assert.throws(() => rolloutRelease([completed], 99), /not on the production track/);
});
