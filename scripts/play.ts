// Builds the mobile and TV release bundles, checks their version and signer,
// and uploads both to the Google Play internal track as one release with
// Gradle Play Publisher. promote rolls that release out to production, and
// rollout changes the production rollout of a code there. Run from the
// repository root:
//
//   node scripts/play.ts build-check --version-name 3.0.0 [--version-code 93]
//   node scripts/play.ts upload --version-name 3.0.0 [--version-code 93]   (CI only)
//   node scripts/play.ts promote --version-code 93 --percent 10            (CI only)
//   node scripts/play.ts rollout --version-code 93 --percent 50            (CI only)
import { execFileSync } from "node:child_process";
import {
  appendFileSync,
  copyFileSync,
  existsSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

import {
  promoteInternalRelease,
  readPlayVersionCodes,
  updateProductionRollout,
} from "./google-play.ts";

const PACKAGE_NAME = "io.put.putio";
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
// The Play upload key's certificate. Play App Signing re-signs what users install.
const UPLOAD_CERT_SHA256 = "c7efb3703c86d265b0be29d768c05cd93e8f3ff7cf171a671313587499771e9f";
const MAX_VERSION_CODE = 2_100_000_000;
// putio-tv-native-legacy's 2.13.0 beta holds 92 on the same package.
const RESERVED_VERSION_CODE = 92;
const SIGNING_ENV = [
  "ANDROID_KEYSTORE_BASE64",
  "ANDROID_KEYSTORE_PASSWORD",
  "ANDROID_KEY_ALIAS",
  "ANDROID_KEY_PASSWORD",
] as const;

// Phones and tablets match only the mobile bundle, which needs no touchscreen;
// TVs match both, and Play serves the highest code, so TV takes the higher one.
const APPS = ["mobile", "tv"] as const;
type App = (typeof APPS)[number];
export type VersionCodes = Readonly<Record<App, number>>;

const aabPath = (app: App): string =>
  join(ROOT, app, `build/outputs/bundle/productionRelease/${app}-production-release.aab`);
const apkDir = (app: App): string => join(ROOT, app, "build/outputs/apk/production/release");

function fail(message: string): never {
  throw new Error(message);
}

const requiredEnv = (name: string): string => process.env[name] || fail(`${name} is not set`);

const run = (command: string, args: readonly string[]): string =>
  execFileSync(command, args, { cwd: ROOT, encoding: "utf8", maxBuffer: 64 << 20 });

const gradle = (args: readonly string[], env: NodeJS.ProcessEnv = process.env): void => {
  // No configuration cache: its entries would keep the signing passwords on disk.
  execFileSync(join(ROOT, "gradlew"), ["--no-daemon", "--no-configuration-cache", ...args], {
    cwd: ROOT,
    env,
    stdio: "inherit",
  });
};

export const parseVersionCode = (value: string, label: string): number => {
  if (!/^[1-9]\d{0,9}$/.test(value))
    fail(`${label} must be a positive integer, got ${JSON.stringify(value)}`);
  const code = Number(value);
  if (code > MAX_VERSION_CODE) fail(`${label} ${code} is above ${MAX_VERSION_CODE}`);
  return code;
};

// Mobile takes the requested code, or the lowest one above every code Google
// Play has seen and the reserved one; TV takes the next.
export const nextVersionCodes = (
  seen: readonly number[],
  requested: number | undefined,
): VersionCodes => {
  if (seen.length === 0) fail(`Google Play returned no version codes for ${PACKAGE_NAME}`);
  const floor = Math.max(RESERVED_VERSION_CODE, ...seen);
  const mobile = requested ?? floor + 1;
  if (mobile <= floor)
    fail(
      `Version code ${mobile} must be above ${floor}, the highest one Google Play has seen or reserved`,
    );
  return { mobile, tv: parseVersionCode(String(mobile + 1), "The TV version code") };
};

const sdkDir = (): string => {
  const fromEnv = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (fromEnv) return fromEnv;
  const properties = join(ROOT, "local.properties");
  const fromFile = existsSync(properties)
    ? /^sdk\.dir=(.+)$/m.exec(readFileSync(properties, "utf8"))?.[1]?.trim()
    : undefined;
  return fromFile || fail("Set ANDROID_HOME or sdk.dir in local.properties");
};

// AGP picks its own build tools; the newest installed ones read what it builds.
const buildTool = (name: string): string => {
  const root = join(sdkDir(), "build-tools");
  const [newest] = (existsSync(root) ? readdirSync(root) : [])
    .filter((version) => /^\d+\.\d+\.\d+$/.test(version))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  if (!newest) fail(`${root} has no build tools`);
  return join(root, newest, name);
};

// Unsigned builds name their APK *-unsigned.apk; either way there is one.
const apkPath = (app: App): string => {
  const apks = existsSync(apkDir(app))
    ? readdirSync(apkDir(app)).filter((name) => name.endsWith(".apk"))
    : [];
  if (apks.length !== 1) fail(`Expected one APK in ${apkDir(app)}, found ${apks.length}`);
  return join(apkDir(app), apks[0] ?? "");
};

type Badging = Readonly<{ packageName: string; versionCode: number; versionName: string }>;

const apkBadging = (apk: string): Badging => {
  const line = run(buildTool("aapt2"), ["dump", "badging", apk]).split("\n")[0] ?? "";
  return {
    packageName: /name='([^']+)'/.exec(line)?.[1] ?? "",
    versionCode: Number(/versionCode='(\d+)'/.exec(line)?.[1] ?? 0),
    versionName: /versionName='([^']+)'/.exec(line)?.[1] ?? "",
  };
};

// The bundle and APK come from one Gradle run of the same variant, so the
// APK's manifest stands for the bundle's.
const checkVersion = (app: App, versionName: string, versionCode: number): void => {
  const badging = apkBadging(apkPath(app));
  const expected: Badging = { packageName: PACKAGE_NAME, versionName, versionCode };
  for (const [key, value] of Object.entries(expected)) {
    const actual = badging[key as keyof Badging];
    if (actual !== value) fail(`The ${app} APK has ${key} ${actual}, expected ${value}`);
  }
};

const unique = (values: readonly string[]): string[] => [...new Set(values)];

// minSdk 26 skips v1 signing, so apksigner prints each scheme's signer, as "V2 Signer: ...".
const apkSigners = (apk: string): string[] =>
  unique(
    [
      ...run(buildTool("apksigner"), [
        "verify",
        "--print-certs",
        "--min-sdk-version",
        "26",
        apk,
      ]).matchAll(/certificate SHA-256 digest: ([0-9a-f]{64})$/gm),
    ].map((match) => match[1] ?? ""),
  );

const bundleSigners = (aab: string): string[] =>
  unique(
    [
      ...run("keytool", ["-printcert", "-jarfile", aab]).matchAll(
        /SHA256: ((?:[0-9A-F]{2}:){31}[0-9A-F]{2})/g,
      ),
    ].map((match) => (match[1] ?? "").replaceAll(":", "").toLowerCase()),
  );

// Names each output that is not signed by the upload certificate alone.
const uploadSignatureProblems = (): string[] =>
  APPS.flatMap((app) =>
    Object.entries({
      [`${app} bundle`]: bundleSigners(aabPath(app)),
      [`${app} APK`]: apkSigners(apkPath(app)),
    }).flatMap(([label, signers]) =>
      signers.length === 1 && signers[0] === UPLOAD_CERT_SHA256
        ? []
        : [`${label} is signed by ${signers.length === 0 ? "nothing" : signers.join(", ")}`],
    ),
  );

// One Gradle run per app: the version code property applies to every project.
const buildRelease = (versionName: string, codes: VersionCodes, env: NodeJS.ProcessEnv): void => {
  for (const app of APPS) {
    rmSync(aabPath(app), { force: true });
    rmSync(apkDir(app), { recursive: true, force: true });
    gradle(
      [
        `:${app}:bundleProductionRelease`,
        `:${app}:assembleProductionRelease`,
        `-PputioVersionName=${versionName}`,
        `-PputioVersionCode=${codes[app]}`,
      ],
      env,
    );
    if (!existsSync(aabPath(app))) fail(`Gradle did not produce ${aabPath(app)}`);
    checkVersion(app, versionName, codes[app]);
  }
};

const requireCi = (): void => {
  if (process.env.GITHUB_ACTIONS !== "true")
    fail("Google Play steps run only from the GitHub Actions workflows in docs/distribution.md");
};

// Writes the keystore where the Gradle signing config reads it, for `action` only.
const withKeystore = <T>(
  keystore: (path: string) => void,
  action: (env: NodeJS.ProcessEnv) => T,
  env: NodeJS.ProcessEnv = process.env,
): T => {
  const dir = mkdtempSync(join(tmpdir(), "putio-keystore-"));
  try {
    const path = join(dir, "upload.jks");
    keystore(path);
    return action({ ...env, ANDROID_KEYSTORE_PATH: path });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
};

const writeUploadKeystore = (path: string): void => {
  const encoded = requiredEnv("ANDROID_KEYSTORE_BASE64").replace(/\s+/g, "");
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) fail("ANDROID_KEYSTORE_BASE64 is not valid base64");
  writeFileSync(path, Buffer.from(encoded, "base64"), { mode: 0o600, flag: "wx" });
};

const sourceSha = (): string => run("git", ["rev-parse", "HEAD"]).trim();

const githubOutput = (values: Readonly<Record<string, string | number>>): void => {
  const path = process.env.GITHUB_OUTPUT;
  if (!path) return;
  appendFileSync(
    path,
    Object.entries(values)
      .map(([key, value]) => `${key}=${value}\n`)
      .join(""),
  );
};

const jobSummary = (title: string, rows: readonly (readonly [string, string])[]): void => {
  const lines = [
    `### ${title}`,
    "",
    "| | |",
    "| --- | --- |",
    ...rows.map(([key, value]) => `| ${key} | ${value} |`),
    "",
  ];
  const path = process.env.GITHUB_STEP_SUMMARY;
  if (path) appendFileSync(path, `${lines.join("\n")}\n`);
  else process.stdout.write(`${lines.join("\n")}\n`);
};

const runLink = (): string => {
  const {
    GITHUB_SERVER_URL: server,
    GITHUB_REPOSITORY: repository,
    GITHUB_RUN_ID: id,
  } = process.env;
  return server && repository && id
    ? `[${id}](${server}/${repository}/actions/runs/${id})`
    : "local";
};

const codesLabel = (codes: VersionCodes): string => `mobile ${codes.mobile}, TV ${codes.tv}`;

const upload = async (versionName: string, requested: number | undefined): Promise<void> => {
  requireCi();
  const credentials = requiredEnv("ANDROID_PUBLISHER_CREDENTIALS");
  for (const name of SIGNING_ENV) requiredEnv(name);
  // A release build without it never starts Sentry (docs/behavior.md#diagnostics).
  requiredEnv("PUTIO_SENTRY_DSN");
  const sha = sourceSha();

  // Every code Google Play has seen: track releases plus every uploaded bundle
  // and APK, putio-tv-native-legacy's included.
  const { tracks, uploaded } = await readPlayVersionCodes(credentials, PACKAGE_NAME);
  const codes = nextVersionCodes([...[...tracks.values()].flat(2), ...uploaded], requested);

  withKeystore(writeUploadKeystore, (env) => buildRelease(versionName, codes, env));
  const problems = uploadSignatureProblems();
  if (problems.length > 0)
    fail(`Refusing to upload: ${problems.join("; ")}. Expected ${UPLOAD_CERT_SHA256}`);

  // The checked bundles alone, not a rebuild: GPP uploads every bundle in the
  // directory as one release. Play keeps release names to 50 characters.
  const artifactDir = mkdtempSync(join(tmpdir(), "putio-play-"));
  for (const app of APPS) copyFileSync(aabPath(app), join(artifactDir, `${app}.aab`));
  gradle([
    ":mobile:publishProductionReleaseBundle",
    "--artifact-dir",
    artifactDir,
    "--track",
    "internal",
    "--release-status",
    "completed",
    "--release-name",
    `${versionName} (${codes.mobile}, ${codes.tv}) ${sha.slice(0, 12)}`,
  ]);

  githubOutput({ mobile_version_code: codes.mobile, tv_version_code: codes.tv });
  jobSummary("Google Play internal", [
    ["Commit", `\`${sha}\``],
    ["Version", `${versionName} (${codesLabel(codes)})`],
    ["Track", "internal"],
    ["Signer", `\`${UPLOAD_CERT_SHA256}\``],
    ["Run", runLink()],
  ]);
};

// Signs with a throwaway key: the signing config must apply, and the upload
// check must reject every signer but the upload key.
const buildCheck = (versionName: string, requested: number | undefined): void => {
  const env: NodeJS.ProcessEnv = { ...process.env };
  for (const name of ["ANDROID_PUBLISHER_CREDENTIALS", "PUTIO_SENTRY_DSN", "SENTRY_AUTH_TOKEN"])
    delete env[name];
  const codes: VersionCodes = {
    mobile: requested ?? RESERVED_VERSION_CODE + 1,
    tv: (requested ?? RESERVED_VERSION_CODE + 1) + 1,
  };
  const password = "putio-build-check";
  withKeystore(
    (path) =>
      run("keytool", [
        "-genkeypair",
        "-keystore",
        path,
        "-storepass",
        password,
        "-alias",
        "build-check",
        "-keypass",
        password,
        "-keyalg",
        "RSA",
        "-keysize",
        "2048",
        "-validity",
        "1",
        "-dname",
        "CN=put.io build check",
      ]),
    (keystoreEnv) => buildRelease(versionName, codes, keystoreEnv),
    {
      ...env,
      ANDROID_KEYSTORE_PASSWORD: password,
      ANDROID_KEY_ALIAS: "build-check",
      ANDROID_KEY_PASSWORD: password,
    },
  );
  const problems = uploadSignatureProblems();
  if (problems.length !== APPS.length * 2)
    fail(`The upload check accepted a throwaway signature: ${problems.join("; ")}`);

  jobSummary("Google Play dry run", [
    ["Commit", `\`${sourceSha()}\``],
    ["Version", `${versionName} (${codesLabel(codes)})`],
    ["Upload signature check", `rejects these outputs: ${problems.join("; ")}`],
    ["Uploaded", "nothing"],
    ["Run", runLink()],
  ]);
};

const promote = async (versionCode: number, percent: number): Promise<void> => {
  requireCi();
  const codes = await promoteInternalRelease(
    requiredEnv("ANDROID_PUBLISHER_CREDENTIALS"),
    PACKAGE_NAME,
    versionCode,
    percent,
  );
  githubOutput({ version_codes: codes.join(",") });
};

const rollout = async (versionCode: number, percent: number): Promise<void> => {
  requireCi();
  await updateProductionRollout(
    requiredEnv("ANDROID_PUBLISHER_CREDENTIALS"),
    PACKAGE_NAME,
    versionCode,
    percent,
  );
};

const main = async (): Promise<void> => {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      "version-name": { type: "string" },
      "version-code": { type: "string" },
      percent: { type: "string" },
    },
  });
  const rawCode = values["version-code"];
  const versionCode = rawCode ? parseVersionCode(rawCode, "--version-code") : undefined;
  const versionName = (): string => {
    const value = values["version-name"] ?? fail("--version-name is required");
    if (!/^\d{1,4}\.\d{1,4}\.\d{1,4}$/.test(value))
      fail(`--version-name must look like 3.0.0, got ${value}`);
    return value;
  };
  const percent = (): number => {
    const value = values.percent ?? "";
    if (!/^([1-9]\d?|100)$/.test(value)) fail("--percent must be an integer from 1 to 100");
    return Number(value);
  };

  switch (positionals[0]) {
    case "upload":
      return upload(versionName(), versionCode);
    case "build-check":
      return buildCheck(versionName(), versionCode);
    case "promote":
      return promote(versionCode ?? fail("--version-code is required"), percent());
    case "rollout":
      return rollout(versionCode ?? fail("--version-code is required"), percent());
    default:
      fail(
        "usage: node scripts/play.ts <upload|build-check|promote|rollout> [--version-name X.Y.Z] [--version-code N] [--percent 1-100]",
      );
  }
};

if (import.meta.main) await main();
