# Changelog

Notable changes to the DIDWW Verification SDK for Android.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html): from 1.0.0 onwards a breaking change
to the public surface requires a major version. The three artifacts version together.

## [1.0.0] — 2026-09

First public release.

### Added

- **Both channels behind one API** — SMS and callout, end to end against the live API by manual
  code entry. `DidwwVerification.start(destination, method, sms, callout)` dispatches at
  runtime; `SmsVerification` and `CalloutVerification` are the same code path addressed
  directly. Options are named after the channel they belong to, and supplying them for a
  different channel is refused with `IllegalArgumentException` at the call site rather than
  silently dropped by the server.

- **A `Flow`-based state machine.** `VerificationHandle.states` emits `Starting`,
  `AwaitingInput`, `Captured`, `Submitting`, `Verified`, `Failed`, `Denied`, `SetupError` and
  `Expired`. A rejected submission returns to `AwaitingInput` with `lastError` set rather than
  ending the flow, so "waiting for the first code" and "that code was wrong" are distinct
  without the SDK counting attempts locally.

- **Recovering a verification without its handle** — `DidwwVerification.resume(destination,
  method)`, and `resume(destination)` on each channel class. A verification outlives the process
  that started it; the handle does not. Resuming looks the destination up on the API's by-number
  routes and drives whatever it finds through the same state machine `start` uses, with
  submissions addressed by destination rather than by id. `Failed` carrying
  `ApiErrorCode.NOT_FOUND` when the number has no verification at all — never a silent create,
  which would supersede the live one, bill the account again, and invalidate the code the user is
  already holding. The number goes on the wire as digits only, whatever the caller formatted, so
  both this SDK and the iOS one send the same bytes for the same destination; a destination with
  no digits in it is refused with `IllegalArgumentException` before any request.

- **A typed, fail-open error model.** `ApiErrorItem` carries the raw slug (`code`), display
  text (`detail`), and the typed `ApiErrorCode` in `known` — `null` for a slug this version
  does not recognise. Decoding never fails on an unknown slug and never loses it. `SdkError`
  covers the client side: `Transport`, `Decoding`, `Superseded`, `AlreadyRunning`.

- **`SetupError` as a distinct terminal state**, for an application misconfiguration that no
  user input can rescue — principally a public-auth application with no `callback_url`, which
  is denied on every start, forever, and would otherwise look like a bad phone number.

- **Three artifacts**, split on the one boundary measurement justifies: `verification-sms` is
  the only module that pulls Play Services, and with it a content provider, an exported
  broadcast receiver and the androidx fragment/activity/lifecycle chain. `verification-core`
  (transport, error model, state machine, callout) and `verification-all` (the umbrella
  entrypoint) cost none of it. Measured in `docs/manifest-cost.md` and frozen by a check.

- **SMS auto-capture, gated on the wire.** The SDK computes this app's SMS Retriever hash from
  the certificate the installed APK is signed with, sends it on every SMS verification, and arms
  the Retriever only when the create response echoes the same hash back. A backend that echoes it
  turns capture on for every already-installed copy, with no SDK release. One that does not is
  never noticed — Play Services is never touched. Nothing to configure either way, including
  under Play App Signing. See `verification-sms/README.md`.

- **`AwaitingInput.sms` carries `interceptionTimeoutSeconds`** alongside `template` — how long
  the server considers an on-device listener worth keeping armed. The SDK stops listening at
  that point or when the verification ends, whichever is sooner; running out stops capture and
  nothing else. It is a budget for auto-fill, never a deadline, and `expires_at` is still the
  only thing that ends a verification.

- **Language selection on both content channels** — `SmsOptions(languages)` picks the message
  template, `CalloutOptions(languages)` picks the recording the call announces the code with.
  Both take the same BCP-47 tags with the same meaning, so one preference list from the host
  serves either channel. Tags are matched exactly, so the region subtag is required (`"pt"` is
  not `pt-PT`); a malformed one is rejected as `ApiErrorCode.LANGUAGES_INVALID`, while a
  well-formed tag the server has nothing behind falls back to `en-US` rather than failing —
  which is what a callout asking for `ka-GE`, `mt-MT`, `ru-RU` or `sq-AL` gets, those having an
  SMS template but no announcement audio.

- **`AwaitingInput.callout`, and `language` on both channel objects.** The server reports the
  tag it *chose* — the first requested one it could serve, or its `en-US` fallback — rather than
  echoing the request, so comparing the two is how a host detects a fallback instead of having
  to model the catalogue itself. `callout` is non-null exactly when the verification is a
  callout, on the same terms as `sms`, so either doubles as the channel discriminator.

- **`ApiErrorCode.APP_HASH_INVALID`**, for a rejected app hash. The SDK only ever sends a
  well-formed one, so this is here for completeness and for hosts inspecting `ApiErrorItem`
  directly.

- **`Environment.Production` / `Sandbox` / `Custom`**, as a constructor parameter defaulting to
  production, so reaching a non-production host is a deliberate choice rather than a silent
  fallback.

- **`Auth.Public` and `Auth.Basic`**, named after the API's auth modes rather than the
  `Authorization` token, which is `Application` for two of them. `Auth.Basic` logs a runtime
  warning when used in a build the host has not marked debuggable, because its secret is
  recoverable from any APK containing it. The signed `application` mode is not implemented —
  it needs that same secret on the device — so raising an application's minimum auth mode
  above `public` rejects every call this SDK makes.

- **A Compose sample app** (`:sample`) covering every channel and every state, with a
  from-nothing setup guide for macOS and Linux, an emulator or a real handset, in
  `sample/README.md`.

- **`LICENSE`** (MIT, matching the DIDWW Verification SDK for iOS), `SECURITY.md`,
  `CONTRIBUTING.md`, and this changelog.

### Deliberately not included

Recorded here because each is a decision rather than an omission, and each would otherwise look
like an unfinished feature:

- **No callout auto-capture.** A spoken code is not readable by an app without recording the
  call, so callout is manual entry, always. SMS is the only channel with an automatic path.

- **No verification policy compiled into the client.** No code length, no attempt count, no TTL,
  no invented interception timeout, no poll interval. Every one of those is the server's to
  choose: `expires_at` is the only deadline, `too_many_attempts` the only attempt limit, and the
  interception budget is read off the wire rather than guessed. Enforced by a build check, not by
  convention — see `CONTRIBUTING.md`.

- **No `stop()`.** `states` is cold; cancelling collection cancels the request and tears down
  whatever the channel registered. A second collection on the same handle is refused with
  `SdkError.AlreadyRunning` rather than issuing a second `POST`, because a screen rotation
  issuing two verifications would have the server supersede the first and bill the account
  twice.

### Known limits

- **Supersession by another process or device is undetectable** until this handle's next
  request comes back rejected. The API has no push channel and publishes no poll interval, so
  there is nothing to observe. In-process supersession *is* detected and reported immediately.

- **SMS auto-capture is not proven end to end by the suite.** The tests drive a fixture this
  repository authors, so they prove the gate is self-consistent and that the extractor handles
  the delivered body's framing — not that a real handset, a real carrier and Play services agree.
  Only a real device and a real message prove that.

- **A message delivered before the create response is decoded is missed** by automatic capture.
  The capability signal is in that response, so there is nothing to arm earlier. Rare, and the
  fallback is manual entry, which is live from the first moment.

- **No `@JvmOverloads`.** Every entrypoint is a Kotlin function with default arguments, which a
  Java caller sees as a single overload requiring every parameter.
