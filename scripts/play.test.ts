import assert from "node:assert/strict";
import { test } from "node:test";

import { productionReleases } from "./google-play.ts";
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
