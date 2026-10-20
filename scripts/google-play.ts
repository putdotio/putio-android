// Reads which version codes Google Play holds for a package, through a
// throwaway edit that is always deleted, and changes the production track.
// Gradle Play Publisher uploads, but reports neither uploaded bundles nor which
// track holds a code, and its promotion of one code drops the other app's.
import { createSign } from "node:crypto";

const API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications";
const SCOPE = "https://www.googleapis.com/auth/androidpublisher";

export type PlayVersionCodes = Readonly<{
  // Each release's version codes, by track name.
  tracks: ReadonlyMap<string, readonly (readonly number[])[]>;
  // Every uploaded bundle and APK, released or not.
  uploaded: readonly number[];
}>;

export type JsonObject = { [key: string]: unknown };

const isObject = (value: unknown): value is JsonObject =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const arrayAt = (value: JsonObject, key: string, context: string): unknown[] => {
  const field = value[key];
  if (field === undefined) return [];
  if (!Array.isArray(field)) throw new Error(`${context}.${key} is not an array`);
  return field;
};

const versionCode = (value: unknown, context: string): number => {
  const code = typeof value === "string" ? Number(value) : value;
  if (typeof code !== "number" || !Number.isSafeInteger(code) || code < 1) {
    throw new Error(`${context} has version code ${JSON.stringify(value)}`);
  }
  return code;
};

const parseCredentials = (
  json: string,
): Readonly<{ email: string; key: string; tokenUri: string }> => {
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch {
    throw new Error("the Google Play service account key is not JSON");
  }
  if (!isObject(raw) || raw.type !== "service_account")
    throw new Error("the Google Play key is not a service account key");
  const { client_email: email, private_key: key, token_uri: tokenUri } = raw;
  if (
    typeof email !== "string" ||
    typeof key !== "string" ||
    typeof tokenUri !== "string" ||
    !tokenUri.startsWith("https://")
  ) {
    throw new Error(
      "the Google Play service account key lacks client_email, private_key or an https token_uri",
    );
  }
  return { email, key, tokenUri };
};

const base64Url = (value: string | Buffer): string => Buffer.from(value).toString("base64url");

const accessToken = async (credentialsJson: string): Promise<string> => {
  const { email, key, tokenUri } = parseCredentials(credentialsJson);
  const now = Math.floor(Date.now() / 1000);
  const unsigned = [
    base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" })),
    base64Url(
      JSON.stringify({ iss: email, scope: SCOPE, aud: tokenUri, iat: now, exp: now + 600 }),
    ),
  ].join(".");
  const signature = createSign("RSA-SHA256").update(unsigned).sign(key);
  const response = await fetch(tokenUri, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${unsigned}.${base64Url(signature)}`,
    }),
  });
  const body: unknown = await response.json().catch(() => undefined);
  if (!response.ok || !isObject(body) || typeof body.access_token !== "string") {
    // Only the OAuth error code, such as invalid_grant; the description can echo the request.
    const code =
      isObject(body) && typeof body.error === "string" && /^[a-z_]+$/.test(body.error)
        ? `, ${body.error}`
        : "";
    throw new Error(`Google refused the service account key (HTTP ${response.status}${code})`);
  }
  return body.access_token;
};

type Call = (method: string, path: string, body?: JsonObject) => Promise<JsonObject>;

// Runs `work` inside a new edit, then commits it or, for a read, deletes it.
const withEdit = async <T>(
  credentialsJson: string,
  packageName: string,
  finish: "commit" | "discard",
  work: (call: Call, editId: string) => Promise<T>,
): Promise<T> => {
  const token = await accessToken(credentialsJson);
  const call: Call = async (method, path, body) => {
    const response = await fetch(`${API}/${packageName}/${path}`, {
      method,
      headers: {
        authorization: `Bearer ${token}`,
        ...(body ? { "content-type": "application/json" } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
    });
    const text = await response.text();
    // Google's error bodies can carry request details; the status is enough.
    if (!response.ok)
      throw new Error(
        `Google Play ${method} ${path.split("/").at(-1)} failed with HTTP ${response.status}`,
      );
    const parsed: unknown = text === "" ? {} : JSON.parse(text);
    if (!isObject(parsed)) throw new Error(`Google Play ${method} ${path} returned no object`);
    return parsed;
  };

  const edit = await call("POST", "edits");
  if (typeof edit.id !== "string" || !/^[\w-]+$/.test(edit.id))
    throw new Error("Google Play returned an edit without an ID");
  const editId = edit.id;
  let result: T;
  try {
    result = await work(call, editId);
  } catch (error) {
    // The work's failure is the one worth reporting; an edit left behind expires.
    await call("DELETE", `edits/${editId}`).catch(() => undefined);
    throw error;
  }
  await (finish === "commit"
    ? call("POST", `edits/${editId}:commit`)
    : call("DELETE", `edits/${editId}`));
  return result;
};

const releaseCodes = (release: unknown, track: string): number[] => {
  if (!isObject(release)) throw new Error(`track ${track} has a malformed release`);
  return arrayAt(release, "versionCodes", track).map((code) => versionCode(code, `track ${track}`));
};

export const readPlayVersionCodes = (
  credentialsJson: string,
  packageName: string,
): Promise<PlayVersionCodes> =>
  withEdit(credentialsJson, packageName, "discard", async (call, editId) => {
    const tracks = new Map<string, number[][]>();
    for (const track of arrayAt(await call("GET", `edits/${editId}/tracks`), "tracks", "tracks")) {
      if (!isObject(track) || typeof track.track !== "string")
        throw new Error("Google Play returned a track without a name");
      const name = track.track;
      tracks.set(
        name,
        arrayAt(track, "releases", name).map((release) => releaseCodes(release, name)),
      );
    }
    const uploaded = [
      ...arrayAt(await call("GET", `edits/${editId}/bundles`), "bundles", "bundles"),
      ...arrayAt(await call("GET", `edits/${editId}/apks`), "apks", "apks"),
    ].map((artifact) =>
      versionCode(isObject(artifact) ? artifact.versionCode : artifact, "an uploaded artifact"),
    );
    return { tracks, uploaded };
  });

// The statuses whose rollout can change, as supply allowed.
const ROLLOUT_STATUSES = ["inProgress", "halted", "draft"];

// The production track after `release` rolls out to `percent` of users in
// place of `replaced`, if any. A staged rollout keeps the completed release
// everyone else gets; a full one replaces it. Drafts stay, and any other
// staged or halted release gives way, since a track holds one.
export const productionReleases = (
  releases: readonly unknown[],
  replaced: unknown,
  release: JsonObject,
  percent: number,
): JsonObject[] => {
  // Google allows a user fraction and country targeting only on a staged release.
  const { userFraction: _fraction, countryTargeting, ...rest } = release;
  const updated =
    percent === 100
      ? { ...rest, status: "completed" }
      : {
          ...rest,
          ...(countryTargeting === undefined ? {} : { countryTargeting }),
          status: "inProgress",
          userFraction: percent / 100,
        };
  const kept = releases.filter(
    (other): other is JsonObject =>
      other !== replaced &&
      isObject(other) &&
      (other.status === "draft" || (percent < 100 && other.status === "completed")),
  );
  return [...kept, updated];
};

const productionTrack = async (call: Call, editId: string): Promise<unknown[]> =>
  arrayAt(await call("GET", `edits/${editId}/tracks/production`), "releases", "production");

const putProduction = (call: Call, editId: string, releases: JsonObject[]) =>
  call("PUT", `edits/${editId}/tracks/production`, { track: "production", releases });

// Rolls the internal track's only release out to production with its version
// codes, name and notes, and returns those codes. `code` must be one of them,
// so the release promoted is the one the caller saw uploaded.
export const promoteInternalRelease = (
  credentialsJson: string,
  packageName: string,
  code: number,
  percent: number,
): Promise<readonly number[]> =>
  withEdit(credentialsJson, packageName, "commit", async (call, editId) => {
    const internal = arrayAt(
      await call("GET", `edits/${editId}/tracks/internal`),
      "releases",
      "internal",
    );
    const [only, ...others] = internal;
    const codes = isObject(only) ? releaseCodes(only, "internal") : [];
    if (!isObject(only) || others.length > 0 || !codes.includes(code))
      throw new Error(
        `Version code ${code} must be in the internal track's only release, which holds ${internal.map((release) => `[${releaseCodes(release, "internal").join(", ")}]`).join(" ") || "nothing"}`,
      );
    const { name, releaseNotes } = only;
    const promoted: JsonObject = {
      versionCodes: codes.map(String),
      ...(name === undefined ? {} : { name }),
      ...(releaseNotes === undefined ? {} : { releaseNotes }),
    };
    const production = await productionTrack(call, editId);
    await putProduction(call, editId, productionReleases(production, undefined, promoted, percent));
    return codes;
  });

// Changes the rollout of the staged, halted or draft production release
// holding `code`, keeping its name and notes.
export const updateProductionRollout = (
  credentialsJson: string,
  packageName: string,
  code: number,
  percent: number,
): Promise<void> =>
  withEdit(credentialsJson, packageName, "commit", async (call, editId) => {
    const releases = await productionTrack(call, editId);
    const selected = releases.find((release) => releaseCodes(release, "production").includes(code));
    if (!isObject(selected)) throw new Error(`Version code ${code} is not on the production track`);
    if (typeof selected.status !== "string" || !ROLLOUT_STATUSES.includes(selected.status))
      throw new Error(
        `Version code ${code} is in a ${String(selected.status)} production release; only a staged, halted or draft release's rollout can change`,
      );
    await putProduction(call, editId, productionReleases(releases, selected, selected, percent));
  });
