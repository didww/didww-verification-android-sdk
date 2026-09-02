# Contributing

## Reporting an issue

Open it on [GitHub](https://github.com/didww/didww-verification-android-sdk/issues), with the
SDK version, the module, and the channel involved. A failing test against this repository is
the most useful form. For a suspected vulnerability use private reporting instead — see
[`SECURITY.md`](SECURITY.md).

## Building

macOS and Linux are both supported and both used. In each new terminal:

```bash
source tools/android-env.sh   # finds JDK 17 and the Android SDK on either OS
```

```bash
./gradlew --offline check     # 98 tests, lint, and five guards
./gradlew assembleRelease     # three AARs
```

Requires JDK 17 — exactly 17, any patch release. The toolchain versions in
`gradle/libs.versions.toml` are exact pins rather than floors, and are deliberately **not** the
newest available: a combination proven to work is worth more than a current one. Bump them in a
deliberate change of their own, one dependency at a time, re-running `./gradlew check` — not as
a side effect of unrelated work.

Two pins have consequences that are easy to miss, and both are commented in the catalog:
`minSdk 23` has zero headroom against Play Services' own floor, and the AGP/Kotlin pair is the
one combination this SDK has been verified against.

Starting from nothing — no JDK, no SDK, no device — `sample/README.md` § 1 is the full
first-time sequence for both operating systems.

Tests never touch the network. Robolectric's runtime is staged locally by
`vendorRobolectricRuntime`; the first run needs network access once, and every run after that
works offline. Drop `--offline` for that first run.

## The guards will argue with you

`check` runs five guards beyond the tests, and each exists because something specific went
wrong or nearly did. **Every one has been demonstrated to fail** — deliberately broken, by
removing or corrupting its own input, and the failure observed. That is not a formality: a
guard that quietly stopped working is a guard nothing would notice. Two of them were
themselves broken when first written and were caught precisely this way.

Each guard also fails when its own input is *missing*, rather than passing over an empty set.
A check that cannot fail is worse than no check, because it also removes the suspicion that
would have made someone look.

The ones you are most likely to meet:

- **`literalsGuard`** — a named constant whose name looks like verification policy fails the
  build. The rule is deliberately narrow: the name must match
  `TIMEOUT|EXPIR|ATTEMPT|RETRY|INTERVAL|MAX_` **and** the initializer must be a number. So
  `const val MAX_ATTEMPTS = 3` is caught, while `ApiErrorCode.EXPIRED("expired")` and
  `expiresAtEpochMillis` are not — those are the wire's own vocabulary. A check that
  false-fails on day one gets loosened or deleted. Add named constants to the
  [inventory](#the-literal-inventory) below with a justification.
- **`apiSurfaceGuard` / `apiCheck`** — public API is frozen in `*/api/*.api`. If you change it
  deliberately, run `./gradlew apiDump` and **review the resulting diff in your commit** — it
  is the record of what integrators will have to deal with.

## The literal inventory

Every named numeric or regex constant under `src/main`, with a one-line justification.
`literalsGuard` enforces the table in both directions: a constant missing from it fails the
build, and a row naming a constant that no longer exists fails it too.

It stops at *named* declarations on purpose. "Every numeric literal" is unbounded —
`substring(i + PLACEHOLDER.length)`, `digest.copyOf(9)`, every array index qualifies — and a
hundred-row hand-maintained table gets loosened or deleted within a month. Named
declarations are the ones that encode a *decision*, and a decision is what is worth defending.

| Constant | Value | Where | Why it is not policy |
|---|---|---|---|
| `connectTimeoutMillis` | `15_000` | `Config` | **Allowlisted.** Transport hygiene: `HttpURLConnection` defaults to `0`, meaning wait forever. Never on the wire, not server-tunable. A hung socket is a hang, not a policy. |
| `readTimeoutMillis` | `30_000` | `Config` | **Allowlisted.** Same reason. Larger than connect because a slow response is normal and a slow TCP handshake is not. |
| `SUCCESS_RANGE` | `200..299` | `HttpUrlTransport`, `RealVerificationHandle` | HTTP's own definition of success. Determines which stream carries the body — `inputStream` throws on non-2xx. |
| `PATTERN` | ISO-8601 regex | `Iso8601` | The shape of the timestamps the API emits, not a duration or a limit. Fails open to `null`. |
| `SECONDS_PER_MINUTE` | `60` | `Iso8601` | Calendar arithmetic. |
| `SECONDS_PER_HOUR` | `3_600` | `Iso8601` | Calendar arithmetic. |
| `SECONDS_PER_DAY` | `86_400L` | `Iso8601` | Calendar arithmetic. |
| `MILLIS_PER_SECOND` | `1_000L` | `Iso8601` | Unit conversion. |
| `HASHED_BYTES` | `9` | `AppHash` | Google's SMS Retriever protocol constant: the digest is truncated to nine bytes before base64. Theirs, not ours. |
| `BASE64_CHARS` | `11` | `AppHash` | Google's protocol constant: the app hash appended to a message is exactly eleven characters. |
| `FORMAT` | app-hash regex | `AppHash` | The alphabet and length the API accepts for `app_hash`. Not a policy value — a shape check on something we compute, so a malformed hash is dropped rather than failing the verification. |

### Deliberately absent

Each of these would be compiled-in verification policy, and none exists anywhere in the SDK:

- **Code length.** The server's choice — six digits today. The extractor captures `(\d+)`,
  which asserts a character class and nothing else.
- **Maximum attempts.** Never counted locally. Whether another try is allowed is the server's
  decision and it says so by returning `too_many_attempts`, which is terminal.
- **Verification TTL.** `expires_at` arrives on the wire and is the only deadline.
- **Interception timeout of our own.** The API sends one — `sms.interception_timeout`, in
  seconds — and the SDK honours it by stopping the listener, never by failing the verification.
  What stays forbidden is *inventing* one: if the server sends none, there is no fallback timer,
  because manual entry is live throughout and `expires_at` still bounds the verification. A
  compiled-in number here would be policy; a number read off the wire is not.
- **Poll interval.** The API has no push channel and publishes no interval, so cross-process
  supersession is simply undetectable. Inventing an interval would be inventing policy.

## `lint` is in `check`, and it earns its place

Do not skip it. On its first run it caught two calls that would have thrown
`NoSuchMethodError` on every API 23 device — this SDK's own declared floor — because both
resolve to API 24 overloads that look ordinary in Kotlin:

- `headers.forEach { (name, value) -> … }` resolves to `java.util.Map#forEach` (API 24).
- `teardowns.forEach { … }` resolves to `java.lang.Iterable#forEach` (API 24).

Nothing else in the build would have found these: the unit tests run on a desktop JVM where
both methods exist.

## Conventions

- `explicitApi()` is on. Every public declaration needs an explicit visibility modifier.
- No Activity, Fragment or LifecycleOwner in any public signature. The SDK takes an
  application `Context` and nothing tied to a host's lifecycle; `apiSurfaceGuard` enforces it.
- `verification-core` may depend on coroutines and kotlinx.serialization, and nothing else.
  An allowlist derived from a real dependency resolution fails the build if that changes.
- Comments explain *why*. The code already says what.

## The public surface is frozen

Breaking it requires a major version. The catch that surprises people: adding a parameter to
a Kotlin class is a **binary** break even when it has a default, because the synthetic
default-argument constructor's signature changes. Types here use plain constructors with
default arguments rather than builders — cheap to call, expensive to grow — so a type that
gains a field grows by a secondary constructor, or waits for a major version.

If you are adding a type you expect to grow, say so in the pull request, and say how it will
grow.
