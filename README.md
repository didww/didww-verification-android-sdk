# DIDWW Verification SDK for Android

Kotlin SDK for [DIDWW](https://didww.com)'s phone-number verification API. It covers both
channels — **SMS** and **callout** — behind one coroutine-based API, with a typed error model
and no Activity, Fragment or `LifecycleOwner` anywhere in its public surface.

- **One dependency to add**, and exactly one permission: `INTERNET`.
- **minSdk 23**, Kotlin coroutines, `Flow`-based state.
- **Nothing is stored and nothing is logged** — no code, no phone number, no credential.
- **No verification policy is compiled in.** Code length, attempt limits and deadlines belong
  to the server and arrive on the wire.

```kotlin
val handle = DidwwVerification(context, Auth.Public(APPLICATION_KEY))
    .start("+37112345678", DeliveryMethod.SMS)

lifecycleScope.launch {
    handle.states.collect { state ->
        when (state) {
            is VerificationState.AwaitingInput -> showCodeEntry(state.lastError)
            is VerificationState.Verified      -> onVerified()
            is VerificationState.Failed        -> onFailed(state.reason)
            else                               -> render(state)
        }
    }
}

handle.submit(codeTheUserTyped)   // never blocks; safe before you collect
```

---

## Contents

- [Requirements](#requirements) · [Installation](#installation) · [Quick start](#quick-start)
- [The verification flow](#the-verification-flow) · [Channels](#channels) ·
  [Environments](#environments) · [Authentication](#authentication)
- [Error handling](#error-handling) · [Rules worth knowing](#rules-worth-knowing) ·
  [Resuming](#resuming-a-verification-you-no-longer-have-a-handle-for) ·
  [Automatic code capture](#automatic-code-capture)
- [Artifacts](#artifacts) · [Sample app](#sample-app) ·
  [Building from source](#building-from-source)

---

## Requirements

| | |
|---|---|
| **minSdk** | 23 |
| **compileSdk** | 36 |
| **JDK** | 17 — exactly 17, any patch release |
| **Kotlin** | 2.4.10, coroutines 1.10.2 |

**Kotlin only.** Coroutines and `Flow` are part of the public surface, and every entry point uses
default arguments, so calling this SDK from Java is not a supported integration path.

You also need, on the DIDWW side:

- **An OTP application key.**
- **A `callback_url` on that application**, reachable from DIDWW's servers. This is not optional
  for `Auth.Public` — it authorises each verification in place of a secret, so an application
  without one is denied on *every* verification, forever. The SDK reports that as
  [`SetupError`](#setuperror-is-not-the-users-fault) rather than an ordinary failure, so it is
  not mistaken for a bad phone number.
- **The application's minimum auth mode left at `public`** — see
  [Authentication](#authentication).
- **A positive account balance.** Verifications cost money; the quoted `fee` is on the wire.

## Installation

```kotlin
// settings.gradle.kts — repositories { mavenCentral() }

dependencies {
    implementation("com.didww.android.sdk.verification:verification-all:1.0.0")
}
```

Pick the artifact that matches what you send — see [Artifacts](#artifacts) for why the split
exists and what it saves you.

**Version 1.0.0.** From here on the public surface is stable: a breaking change to it requires a
major version. The three artifacts version together, so they are always upgraded as a set.

## Quick start

A verification is: **start a handle → collect its states → submit what the user gives you.**

```kotlin
import com.didww.android.sdk.verification.*
import com.didww.android.sdk.verification.all.DidwwVerification

class VerifyViewModel(application: Application) : AndroidViewModel(application) {

    private val didww = DidwwVerification(
        context = application,                       // application Context, never an Activity
        auth = Auth.Public(BuildConfig.DIDWW_APPLICATION_KEY),
        environment = Environment.Production,        // the default
    )

    private val _state = MutableStateFlow<VerificationState>(VerificationState.Starting)
    val state: StateFlow<VerificationState> = _state.asStateFlow()

    private var handle: VerificationHandle? = null

    fun start(destination: String) {
        val handle = didww.start(destination, DeliveryMethod.SMS).also { this.handle = it }

        // Collect ONCE, outside the view layer. See "Collect once" below — this is the
        // single rule of this SDK that will bite you if you skip it.
        viewModelScope.launch {
            handle.states.collect { _state.value = it }
        }
    }

    /** Safe to call at any point, including before the first state arrives. */
    fun submit(code: String) {
        handle?.submit(code)
    }
}
```

Render from `state`:

```kotlin
when (val s = state) {
    VerificationState.Starting    -> Spinner("Requesting a code…")
    is VerificationState.AwaitingInput -> CodeEntry(
        hint  = s.sms?.template,                    // SMS only; null on other channels
        error = s.lastError?.detail,                // set when the previous try was rejected
        expiresAt = s.expiresAtEpochMillis,
    )
    VerificationState.Submitting  -> Spinner("Checking…")
    is VerificationState.Verified -> Success()
    is VerificationState.Failed   -> Failure(s.reason)
    is VerificationState.Denied   -> Failure(s.error?.detail)
    is VerificationState.SetupError -> Misconfigured(s.code, s.detail)
    VerificationState.Expired     -> Expired()
    is VerificationState.Captured -> Spinner("Code received")   // see Automatic code capture
}
```

Starting the verification performs **no I/O** — `start()` only builds the handle. The
`POST` goes out when you first collect `states`.

## The verification flow

```
                    start()  ──►  handle          (no I/O yet)
                                    │
                       first collect of .states
                                    ▼
                                 Starting
                                    │
                    ┌───────────────┼───────────────┬──────────────┐
                    ▼               ▼               ▼              ▼
              AwaitingInput      Denied        SetupError       Failed
                    │  ▲          (terminal)   (terminal)      (terminal)
        submit(…)   │  │ lastError set — wrong code, try again
                    ▼  │
                Submitting ──────┘
                    │
         ┌──────────┼──────────┬───────────┐
         ▼          ▼          ▼           ▼
     Verified    Failed     Expired    AwaitingInput
    (terminal)  (terminal) (terminal)   (retry)
```

| State | Meaning | Terminal |
|---|---|---|
| `Starting` | The opening request is in flight — the create, or the by-number lookup for a [resumed](#resuming-a-verification-you-no-longer-have-a-handle-for) handle. | |
| `AwaitingInput` | The server is waiting for a value. Carries `verificationId`, `deliveryMethod`, `destination`, `fee`, `expiresAtEpochMillis`, `sms`, `callout`, and `lastError`. | |
| `Captured` | A value was captured automatically and is about to be submitted. Reached only when the server enables capture for that verification — see [Automatic code capture](#automatic-code-capture). | |
| `Submitting` | A value is in flight. | |
| `Verified` | The server accepted the value. | ✅ |
| `Failed` | Carries a `FailureReason` — either an API error or an SDK-side one. | ✅ |
| `Denied` | The application's callback declined, or answered unreadably. | ✅ |
| `SetupError` | The **application** is misconfigured; no user input can rescue it. | ✅ |
| `Expired` | The deadline passed with no accepted value. | ✅ |

A rejected submission is not terminal: the flow returns to `AwaitingInput` with `lastError`
set, which is how you distinguish *"waiting for the first code"* from *"that code was wrong."*
Whether another attempt is allowed is the server's decision, and it says so by returning
`too_many_attempts`, which **is** terminal.

## Channels

`DidwwVerification` picks the channel at runtime:

```kotlin
val didww = DidwwVerification(context, auth)

didww.start(number, DeliveryMethod.SMS)      // a code by text message
didww.start(number, DeliveryMethod.CALLOUT)  // a spoken code
```

If you only ever use one channel, depend on its class directly and skip the dispatch:

```kotlin
SmsVerification(context, auth).start(number)                    // verification-sms
CalloutVerification(context, auth).start(number)                // verification-core
```

Each of these, and `DidwwVerification`, also has a
[`resume`](#resuming-a-verification-you-no-longer-have-a-handle-for) for reattaching to a
verification that is already running.

These are the same code path — `DidwwVerification` is a `when` over `DeliveryMethod` that
delegates to exactly these classes, sharing one engine, and a parity test asserts the two
produce identical state sequences.

**What the user submits is the code, on either channel** — read off the message on SMS, heard
on the call for callout. The SDK compiles in no code length and no alphabet: whatever the
server sent is what it forwards.

### Per-channel options

Options are named after the channel they belong to, so a channel that gains options later adds
a parameter rather than overloading one slot:

```kotlin
didww.start(number, DeliveryMethod.SMS, sms = SmsOptions(languages = listOf("de-DE")))
didww.start(number, DeliveryMethod.CALLOUT, callout = CalloutOptions(languages = listOf("pt-BR")))
```

`SmsOptions` and `CalloutOptions` are the two that exist, one per channel.
**Passing options for a different channel throws `IllegalArgumentException`** at the call site:

```kotlin
didww.start(number, DeliveryMethod.CALLOUT, sms = SmsOptions(...))   // ⚠️ throws
```

That is deliberate. The server reads only the block matching `delivery_method` and would answer
`201` with its defaults, so the request would silently not be the one you wrote — a mistake
better caught at the call site than discovered from a message arriving in the wrong language.

#### Languages

`languages` is a list of BCP-47 tags, most preferred first — the message template for SMS, the
recording the call plays for callout. **Both channels take the same tags and mean the same thing
by them**, so a host that knows the user's preference passes it either way:

```kotlin
val preferred = listOf("de-DE", "en-US")

when (method) {
    DeliveryMethod.SMS -> didww.start(number, method, sms = SmsOptions(preferred))
    DeliveryMethod.CALLOUT -> didww.start(number, method, callout = CalloutOptions(preferred))
}
```

Two things are worth knowing about how the server matches them:

- **Tags are matched exactly, so the region subtag is required.** `"pt"` does not match the
  `pt-PT` template or the `pt-PT` recording. A *malformed* tag is rejected outright, as
  `ApiErrorCode.LANGUAGES_INVALID`.
- **A well-formed tag with nothing behind it is not an error — it falls back to `en-US`.** The
  two catalogues do not overlap perfectly: `ka-GE`, `mt-MT`, `ru-RU` and `sq-AL` have an SMS
  template and no announcement audio, so a callout asking for one of them is accepted and
  announced in English. A handful of callout tags are served by an approximate recording, too —
  `en-GB` is the US-accented English one, `es-419` a single informal Spanish, `ar-AE`/`ar-EG`/
  `ar-SA` Modern Standard Arabic, `tr-CY` standard Turkish.

Because of that second point, the tag the server **chose** comes back on the response, and it is
the server's answer rather than an echo of the request:

```kotlin
is VerificationState.AwaitingInput -> {
    val spoken  = s.callout?.language    // "en-US" when the requested tag had no recording
    val written = s.sms?.language        // the tag the template was actually rendered in
}
```

Compare it with what you asked for and you know a fallback happened; nothing else in the
response says so.

### What the server reports back

`AwaitingInput` carries one channel object, non-null **exactly when** that is the channel the
verification is on — so `sms != null` and `callout != null` double as the discriminator, even
for a response that carried no block at all.

| | Carries |
|---|---|
| `AwaitingInput.sms` | `template` (the message with `{{CODE}}` still in it), `language`, `interceptionTimeoutSeconds` |
| `AwaitingInput.callout` | `language` |

`interceptionTimeoutSeconds` is **a budget for automatic capture, not a deadline**. It is how
long an on-device listener is worth keeping armed; when it runs out the SDK stops listening and
nothing else happens — the verification is still live, manual entry still works, and
`expiresAtEpochMillis` remains the only thing that ends it. Show it if you want to tell the user
when auto-fill will stop trying; never treat it as time remaining.

## Environments

The environment is a constructor parameter defaulting to `Production`, so reaching a
non-production host is always a deliberate choice rather than a silent fallback. The SDK
appends `/api/v1` itself.

| Environment | Host |
|---|---|
| `Environment.Production` | `https://verification.didww.com` |
| `Environment.Sandbox` | `https://verification-sandbox.didww.com` |
| `Environment.Custom(url)` | any scheme + host — a local backend, a proxy, or tests |

```kotlin
DidwwVerification(context, auth, Environment.Sandbox)
DidwwVerification(context, auth, Environment.Custom("http://10.0.2.2:3000"))
```

Timeouts live in `Config`, which is transport hygiene rather than verification policy:

```kotlin
DidwwVerification(context, auth, Environment.Production,
    Config(connectTimeoutMillis = 15_000, readTimeoutMillis = 30_000))   // the defaults
```

## Authentication

The API ranks three auth modes. This SDK implements the two usable from a device:

| Mode | `Authorization` header | In this SDK | Use |
|---|---|---|---|
| `public` | `Application <key>` | `Auth.Public(applicationKey)` | **Production, on-device** |
| `basic` | `Basic base64(key:secret)` | `Auth.Basic(key, secret)` | Local development only |
| `application` | `Application <key>:<signature>` + `x-timestamp` | — | Server-to-server, not implemented |

The header token is `Application` for two of them, so these are named for the mode.

**Ship `Auth.Public`.** The application key identifies your application but is not a secret —
extracting it from an APK gains an attacker nothing, because the server asks your application's
`callback_url` to authorise each verification.

> ⚠️ **`Auth.Basic` puts a server-to-server secret in your app**, where it is recoverable from
> any APK containing it. The SDK logs a warning at runtime if it is used in a build you have
> not marked debuggable. If you have already shipped it, treat the secret as disclosed and
> rotate it. See [`SECURITY.md`](SECURITY.md).

The signed `application` mode needs that same secret on the device, so it buys a mobile client
nothing and is not implemented.

**Leave your application's minimum auth mode at `public`.** It is set from your DIDWW account,
and raising it rejects every call this SDK makes: `basic` would ship a secret in your APK, and
`application` is unavailable. Raise it only for an application driven by your own server.

## Error handling

Nothing throws for a failed verification — every outcome is a **state**. The only exception is
`IllegalArgumentException` for the channel/options mismatch above, which is a programming error
rather than a verification outcome.

`Failed` carries a `FailureReason`, which is one of two things:

```kotlin
when (val reason = state.reason) {
    is FailureReason.Api -> reason.error       // the server said no — an ApiErrorItem
    is FailureReason.Sdk -> when (reason.error) {
        is SdkError.Transport     -> "offline, timed out, or TLS failed"
        is SdkError.Decoding      -> "the response could not be read"
        SdkError.Superseded       -> "another verification replaced this one"
        SdkError.AlreadyRunning   -> "states was collected twice — see Collect once"
    }
}
```

### `ApiErrorItem` — raw slug, typed when known

Every server-side error arrives as an `ApiErrorItem` with three fields:

- **`code`** — the raw slug string, **always present** (e.g. `"code_invalid"`).
- **`detail`** — human-readable static text. Display it; never branch on it.
- **`known`** — the typed `ApiErrorCode`, or `null` when this SDK version does not recognise
  the slug.

```kotlin
when (error.known) {
    ApiErrorCode.CODE_INVALID       -> showError("That code is not right.")
    ApiErrorCode.TOO_MANY_ATTEMPTS  -> showError("Too many attempts. Start over.")
    ApiErrorCode.BALANCE_INSUFFICIENT,
    ApiErrorCode.UNAUTHORIZED       -> reportToYourBackend(error.code)
    null                            -> showError(error.detail)   // a slug newer than this SDK
    else                            -> showError(error.detail)
}
```

`ApiErrorCode` has **no `.other` case and is fail-open by construction**: an unrecognised slug
makes `known == null` while `code` still carries the raw truth. Decoding never fails and no
slug is ever lost. This matters because the server derives validation slugs mechanically, so it
can mint one this SDK has never seen simply by adding a validation. Branch on `code`/`known`,
never on `detail`.

### `SetupError` is not the user's fault

`SetupError` exists for one situation that is otherwise indistinguishable from a bad phone
number: an application authenticated publicly with **no `callback_url`** returns `201` plus a
denial on *every* start, forever. Surfacing that as an ordinary failure sends a host — and
their user — looking at the phone number.

```kotlin
is VerificationState.SetupError ->
    // Your application is misconfigured. Retrying will not help; no user input can rescue it.
    Log.e("didww", "verification misconfigured: ${state.code} ${state.detail}")
```

Treat it as a bug report against your own configuration, not as a message for the end user.

## Rules worth knowing

### Collect once

**`handle.states` is cold, and may be collected exactly once.** Collection issues the request;
cancelling collection cancels it and tears down anything the channel registered — there is no
`stop()` to forget.

A second collection is **refused**: it emits `Failed(FailureReason.Sdk(SdkError.AlreadyRunning))`
and the refusal is permanent for that handle. This is deliberate — a screen rotation issuing two
verifications would have the server supersede the first and bill your account twice.

In practice this means one thing: **collect from a ViewModel-scoped coroutine, never from a
composable or an Activity that is recreated.** Mirror into a `StateFlow` and render from that,
as the [Quick start](#quick-start) does. To retry, start a new handle.

### Resuming a verification you no longer have a handle for

**A verification outlives the process that started it. The handle does not.** It lives in memory,
is single-use, and is gone after a low-memory kill or a cold start — while the verification is
still running on the server and the user is still holding the code.

`resume` is the way back:

```kotlin
// Persist this much and nothing else.
prefs.edit().putString("didww_destination", number).apply()

// After the process is recreated:
val handle = didww.resume(number, DeliveryMethod.SMS)
viewModelScope.launch { handle.states.collect { _state.value = it } }
```

It emits exactly what `start` emits, from `Starting` onwards, and `submit()` works the same way —
submissions are addressed by destination rather than by an id you would otherwise have had to
persist as well.

**Do not fall back to `start` instead.** Starting a second verification supersedes the first, bills
your account again, *and* invalidates the code the user already received. `resume` looks the
number up and tells you what is actually there: `AwaitingInput` while one is live, its terminal
state when the one it found is already over, and `Failed` with `ApiErrorCode.NOT_FOUND` when the
number has no verification at all.

The `method` argument selects the same per-channel machinery `start` would — for SMS, the
automatic-capture gate. It is not an assertion about the verification: that was started elsewhere,
so the server's own `delivery_method` is what the SDK reports against and what reaches
`AwaitingInput.deliveryMethod`.

The number is reduced to its digits for the by-number path, so any format you have stored will do.
A string with **no digits in it at all** throws `IllegalArgumentException` before any request —
there is no by-number path to look it up under.

### `submit()` never blocks, but the server may not be ready yet

The submission sink exists as soon as the handle does, so a value offered before
`AwaitingInput` arrives is buffered rather than dropped, and `submit()` itself never blocks and
never throws. Your UI does not have to wait for a network round trip before it unlocks.

**Acceptance is a separate question.** A report is only accepted once the verification has
actually been dispatched — the SMS sent, the call placed. Submitted before that, it comes back
`ApiErrorCode.NOT_READY_TO_REPORT`, which is **retryable**: the state machine returns to
`AwaitingInput` with `lastError` set, exactly as it does for a wrong code, and the same value
submitted a moment later is accepted.

Nothing on the wire distinguishes the two, so there is nothing to poll and nothing to gate on:
`status` reads `pending` from the moment the verification is created. In practice the window
rarely matters — in every channel the user learns the value *from* the delivery, so by the time
they have something to type the dispatch has happened. It matters if you submit a value the
user already had, so treat `NOT_READY_TO_REPORT` as "try again", never as a failed attempt.

### Expiry counts elapsed time, not the wall clock

`AwaitingInput.expiresAtEpochMillis` is the server's deadline and the only one — the SDK
compiles in no TTL. The countdown runs on elapsed time, because an NTP correction or a user
changing the date moves the wall clock by hours mid-verification, which against a two-minute
deadline is fatal in both directions.

`Expired` may be emitted locally from that countdown as a UX affordance, but the **server stays
authoritative**: a value submitted late is still sent, and `Expired` is never emitted while a
submission is in flight.

### Supersession by another device is not observable

Starting a verification for a number that already has an active one **supersedes** it. In-process
this is detected and reported immediately as `SdkError.Superseded`. A verification superseded by
**another process or device** is undetectable until this handle's next request comes back
rejected: the API has no push channel and publishes no poll interval, so there is nothing to
observe — and inventing an interval would mean compiling a server-owned policy decision into the
client.

## Automatic code capture

**Always write the manual path. It is the one that always works.** Both channels work end to
end by manual entry, with the full error model, state machine, expiry handling and cancellation,
and manual entry stays live even while automatic capture is armed.

**SMS auto-capture is decided by the server, per verification.** The SDK computes this app's
SMS Retriever hash, sends it on every SMS verification, and arms the Retriever only when the
create response echoes that same hash back. A backend that echoes it turns capture on for every
copy of this SDK already installed on every handset — with no SDK release, no recompile on your
side, and no coordination. A backend that does not is never noticed: Play services is never
touched and the user types the code.

So handle `VerificationState.Captured` as a normal part of your flow rather than a future one.
The mechanism, its limits, and what it costs you are in
[`verification-sms/README.md`](verification-sms/README.md).

**Callout has no capture path at all**, and is not waiting on one: a spoken code is not
readable by an app without recording the call. Callout verifications are manual entry, always.

## Artifacts

```
com.didww.android.sdk.verification:verification-core   transport, error model, state machine,
                                                       callout
                                  :verification-sms    + Play Services
                                  :verification-all    umbrella, DidwwVerification
```

Depend on **`verification-all`** for the runtime-dispatch entrypoint, **`verification-sms`** if
you only send SMS, or **`verification-core`** alone if you only use callout.

**The split is exactly one bit wide, and it is not cosmetic.** `verification-sms` is the only
module that pulls Play Services, and with it a content provider, an *exported* broadcast
receiver and the whole androidx fragment/activity/lifecycle chain. A host that never sends an
SMS pays none of it. The exact measured cost of each choice is in
[`docs/manifest-cost.md`](docs/manifest-cost.md), frozen by a build check that fails if it ever
changes.

Callout lives *in* `verification-core` rather than in a coordinate of its own because
measurement showed separating it would buy nothing: it adds no dependency, and contributes not
a single merged-manifest element.

**The only permission this SDK declares is `INTERNET`.**

## Sample app

`:sample` is a working Compose demo of the whole flow — every channel, every state, against a
live backend or a local one.

[**`sample/README.md`**](sample/README.md) is also the from-nothing setup guide: JDK, Android
SDK, and either an emulator or a real handset, on **macOS or Linux**.

```bash
source tools/android-env.sh
./gradlew :sample:installDebug
```

## Building from source

Builds on **macOS and Linux**. In each new terminal:

```bash
source tools/android-env.sh   # finds JDK 17 and the Android SDK on either OS
```

```bash
./gradlew assembleRelease     # three AARs
./gradlew --offline test      # 98 tests, no network
./gradlew --offline check     # tests, lint, and five guards
```

`--offline` works from the second run onwards. The first run fetches Robolectric's `android-all`
runtime — which Robolectric resolves through its own Maven client rather than through Gradle —
and stages it locally so every later run is genuinely network-free.

`check` runs five guards beyond the tests — public-API surface, no `@RequiresApi` on a public
declaration, no compiled-in verification policy, the merged-manifest cost, and
`verification-core`'s dependency closure. Each has been **demonstrated to fail** by corrupting
its own input, and each fails when its own input is missing rather than passing over an empty
set. [`CONTRIBUTING.md`](CONTRIBUTING.md) has the ones you are most likely to meet.

The same `check` runs on every push and pull request — see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml). It builds and tests only; releases are
cut by hand.

Contributing: [`CONTRIBUTING.md`](CONTRIBUTING.md). Security: [`SECURITY.md`](SECURITY.md).
Licence: [MIT](LICENSE), matching the DIDWW Verification SDK for iOS.
