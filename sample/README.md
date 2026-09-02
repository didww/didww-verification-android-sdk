# `:sample` — the DIDWW Verification SDK demo app

A small Compose app that drives the SDK's public API against a backend you choose, and makes
**every state the SDK can reach** visible and reproducible.

It exists for two reasons:

1. **To exercise the SDK from the outside.** Every state transition on screen comes from a
   real `DidwwVerification` call. Nothing is faked.
2. **To be a real R8 subject.** The release variant is minified and resource-shrunk, so the
   SDK's consumer ProGuard rules are exercised exactly as they would be in a customer's app.
   The unit tests run on a desktop JVM with no R8 anywhere, so this is the only place those
   rules are tested at all.

It builds and runs on **macOS and Linux**, on an **emulator or a real handset**. Written for
someone with no Android experience; if a step looks like it assumes knowledge you do not have,
that is a bug in this file.

---

## 1. One-time setup

Three things: a JDK 17, the Android SDK, and something to run the app on. If you already have
Android Studio installed and working, you have the first two — skip to [§1.3](#13-load-the-toolchain-into-your-shell).

### 1.1 A JDK 17

Exactly 17. Not 21, not 25: the build runs on AGP 8.13.2, and every module declares
`jvmToolchain(17)`. Any 17.x will do — this is not pinned to a patch release.

| | |
|---|---|
| macOS | `brew install --cask temurin@17` |
| Debian / Ubuntu | `sudo apt-get install -y openjdk-17-jdk` |
| Fedora / RHEL | `sudo dnf install -y java-17-openjdk-devel` |
| Arch | `sudo pacman -S jdk17-openjdk` |
| Anything, via SDKMAN | `sdk install java 17.0.20-tem` |

### 1.2 The Android SDK

Installing **Android Studio** gives you one, at `~/Library/Android/sdk` on macOS and
`~/Android/Sdk` on Linux, and is the easiest route if you want an IDE anyway.

For a headless machine, or if you would rather not install an IDE, the command-line tools are
enough. Roughly 2 GB in total:

```bash
# macOS: SDK_ROOT=~/Library/Android/sdk   TOOLS_OS=mac
# Linux: SDK_ROOT=~/Android/Sdk           TOOLS_OS=linux
SDK_ROOT=~/Android/Sdk
TOOLS_OS=linux

mkdir -p "$SDK_ROOT/cmdline-tools"
curl -fsSL -o /tmp/cmdline-tools.zip \
  "https://dl.google.com/android/repository/commandlinetools-${TOOLS_OS}-13114758_latest.zip"
unzip -q /tmp/cmdline-tools.zip -d "$SDK_ROOT/cmdline-tools"
mv "$SDK_ROOT/cmdline-tools/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"

export ANDROID_HOME="$SDK_ROOT"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;35.0.0"
```

`build-tools;35.0.0` is not a typo next to `android-36`: AGP 8.13.2 compiles against API 36 but
pins its own build-tools default at 35.0.0, and that is the one the build actually loads. If
you install a different one, AGP downloads 35.0.0 on the first build anyway — accepting the
licence non-interactively only because `sdkmanager --licenses` was run above.

`cmdline-tools/latest` is not a stylistic choice — `sdkmanager` locates the SDK root by walking
up from its own path, and installs everything one level too high if the directory is named
anything else.

> If `commandlinetools-…-13114758_latest.zip` 404s, Google has replaced that build. The current
> URL is on <https://developer.android.com/studio#command-line-tools-only>; only the build
> number changes.

### 1.3 Load the toolchain into your shell

**Every time you open a new terminal**, from the repository root:

```bash
source tools/android-env.sh
```

It finds your JDK 17 and your SDK on either operating system, exports `JAVA_HOME`,
`ANDROID_HOME` and `PATH`, and prints what it picked. If something is missing it names it and
prints the command that installs it. An existing `JAVA_HOME` that already points at a 17 is
kept, so jenv, SDKMAN, asdf and mise users keep the JDK they chose.

It is deliberately not something you put in `~/.zshrc` or `~/.bashrc`: `JAVA_HOME` is a
machine-wide setting, and other projects on the same machine may need a different JDK.

If you skip this step, `./gradlew` fails with a Java version error, or cannot find the SDK.

## 2. Something to run it on

Either works, and the app is identical on both. An **emulator** is faster to get going and is
what the state table in [§6](#6-reaching-each-state-deliberately) assumes; a **real handset**
is the more faithful test and the only way to see a real SMS or a real call arrive.

### 2a. An emulator

The system image has to match your *host* CPU, or it runs under full instruction emulation and
is unusably slow:

| Host | ABI |
|---|---|
| Apple Silicon Mac | `arm64-v8a` |
| Intel Mac | `x86_64` |
| Linux (almost always) | `x86_64` |
| Linux on ARM | `arm64-v8a` |

```bash
ABI=x86_64        # or arm64-v8a, per the table
sdkmanager --install "system-images;android-36;google_apis_playstore;$ABI"
avdmanager create avd -n didww_sdk_api36 \
  -k "system-images;android-36;google_apis_playstore;$ABI" -d pixel_7
emulator -avd didww_sdk_api36 &
```

`avdmanager create` prints two lines beginning `Error: Could not load devices from
…/devices.xml` on some system images. **They are cosmetic** — the AVD is created correctly and
`avdmanager list avd` will show it. It also does not prompt for a hardware profile as long as
`-d` is given and the package has a single ABI, so the command above is safe to paste.

About 25 seconds to boot. Check it is up:

```bash
adb devices          # should list  emulator-5554   device
```

**On Linux the emulator needs KVM**, and says so in a way that is easy to misread as a
corrupt AVD. If `emulator` reports it cannot open `/dev/kvm`:

```bash
sudo apt-get install -y qemu-kvm            # or your distro's equivalent
sudo usermod -aG kvm "$USER"                # then log out and back in
kvm-ok                                      # Debian/Ubuntu: should say KVM acceleration can be used
```

Inside a VM or a container this may be unavailable entirely, in which case use a real device.

### 2b. A real device

The more realistic target, and the one to use when a real message has to arrive.

1. **Android 6.0 (API 23) or newer.** That is the SDK's declared floor; anything older will
   not install.
2. **Enable USB debugging.** Settings → About phone → tap *Build number* seven times, then
   Settings → System → Developer options → *USB debugging*.
3. Plug it in and accept the *Allow USB debugging?* prompt on the phone.

```bash
adb devices          # should list your device's serial, then  device
```

**On Linux, expect `no permissions` the first time.** USB devices are not accessible to
ordinary users by default, and this is not a phone problem:

```bash
sudo usermod -aG plugdev "$USER"            # then log out and back in
sudo apt-get install -y android-sdk-platform-tools-common   # ships the udev rules
sudo udevadm control --reload-rules && sudo udevadm trigger
adb kill-server && adb devices
```

Distributions without that package need a rule of their own in
`/etc/udev/rules.d/51-android.rules`; `lsusb` gives the vendor id to put in it. macOS needs
none of this — it has no equivalent permission layer for USB.

If the device shows as `unauthorized`, the on-phone prompt was dismissed. `adb kill-server`
and reconnect to get it again.

## 3. Build and install

```bash
cd <this repository>
./gradlew :sample:installDebug
```

With both an emulator and a phone attached, `adb` refuses to choose. Either detach one, or
name the target: `./gradlew :sample:installDebug -Pandroid.injected.device.serial=<serial>`,
taking the serial from `adb devices`.

Then open **DIDWW Verification Sample** from the app drawer, or launch it directly:

```bash
adb shell am start -n com.didww.android.sdk.verification.sample/.SampleActivity
```

To exercise the minified build instead — which is the one that proves the ProGuard rules —
use `./gradlew :sample:installRelease`. Both variants are signed with the debug key so that
`installRelease` works at all; a real app would never do that.

### If you prefer Android Studio

It works, with **one setting you must change first**, or the build fails in a way that has
nothing to do with this project:

> **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK**
> Set it to a **JDK 17**, **not** the bundled JetBrains Runtime that Android Studio selects by
> default. `source tools/android-env.sh && echo $JAVA_HOME` prints the path to enter.

Android Studio will also offer to upgrade the Android Gradle Plugin. **Decline.** The
AGP 8.13.2 / Kotlin 2.4.10 / Gradle 8.14.5 combination is pinned deliberately — see the header
of `gradle/libs.versions.toml`.

## 4. Point it at a backend

Configuration is the app's first screen, and is reachable again from **Configuration** on the
start screen. Three options:

| Environment | Base URL | Notes |
|---|---|---|
| **Sandbox** (default) | `https://verification-sandbox.didww.com` | Built into the SDK. Start here. |
| Production | `https://verification.didww.com` | Built into the SDK. Real deliveries, real charges. |
| Custom URL | anything | A proxy, a test server, or a backend you run yourself |

**Sandbox is where this demo points out of the box**, and where you should stay until the flow
works end to end: it exercises the same API without spending anything on real deliveries. All
you need is a sandbox application `uuid` with a `callback_url` on it.

### Reaching a backend you run yourself

Only if you have one — skip this if you are on Sandbox. Neither an emulator nor a phone can
see your machine's `localhost`; each has a loopback of its own. The route differs by target,
and this is the single most common reason the demo appears to hang on `Starting`:

| Target | URL to enter | Why |
|---|---|---|
| Emulator | `http://10.0.2.2:3000` | `10.0.2.2` is the emulator's standing alias for the host's loopback interface. |
| Real device | `http://localhost:3000`, after `adb reverse tcp:3000 tcp:3000` | There is no `10.0.2.2` on a handset. `adb reverse` routes the device's own loopback back down the USB cable to the host. |

`adb reverse` does not survive a reconnect or an `adb kill-server`; re-run it after either.

Plain `http` works for both because `res/xml/network_security_config.xml` opts in for
`10.0.2.2`, `localhost` and `127.0.0.1` **only**. It is not a blanket
`cleartextTrafficPermitted="true"`, which would silently disable TLS enforcement for the real
sandbox and production hosts too.

### Credentials

Enter the application `uuid` as the **Application key**. **Basic** additionally takes that
application's secret; the Application-key scheme sends no secret at all.

Everything is saved to `SharedPreferences` as you type, so it is still there next launch. The
write is `apply()`, which is asynchronous — it lands within milliseconds and survives
`am force-stop` in practice, but it is not a guarantee against a `SIGKILL` in the same instant.
This is a local test tool holding a sandbox key; a shipping app would not store credentials
this way.

> **Application-key auth has a precondition.** The application must have a `callback_url`
> configured. A public key is by definition extractable from an app, so the backend asks the
> application's own callback whether to allow each start. With no `callback_url` it fails
> closed: the verification is created **already denied**, returned as HTTP 201 with
> `status: denied`, and the SDK surfaces it as **`SetupError`** — not as an HTTP error.

## 5. Run a verification

1. Enter a destination in E.164 (`+37112345678`) and pick a channel.
2. Tap **Send code**.
3. **Read the expected value out of the backend** — it is never returned over the API. Which
   column depends on the channel:

   | Channel | What you submit | Where to read it |
   |---|---|---|
   | SMS | the code | the verification's `code` |
   | Callout | the code (spoken aloud on a real call) | the verification's `code`, same as SMS |

   Against a real account and a real handset you do not need any of this — the message or call
   arrives and you read the value off the device. The lookup above is for a backend you run
   yourself, or for an emulator, where nothing is actually delivered.
4. Type it in and tap **Verify**.

**Nothing arrives on an emulator.** No SMS, no calls, whatever the backend. That is expected:
on an emulator every channel is driven by reading the value from the backend and typing it.
Seeing a message actually arrive needs a real handset, a real phone number and a real account.

## 6. Reaching each state deliberately

The **Raw state log** at the bottom of the code screen records every emission with a timestamp
and a generation number, and is never cleared. It is the most useful thing on the screen.

| State | How to reach it |
|---|---|
| `Starting` | Any start. |
| `AwaitingInput` | Any successful create. Shows id, method, destination, fee, template and a live countdown. |
| `AwaitingInput` with `lastError` | Submit a wrong code. `code_invalid` is *retryable*, so the verification stays live. |
| `Submitting` | Tap **Verify**. |
| `Verified` | Submit the correct code. |
| `Failed(api=…)` | Exhaust the attempts — the server answers `too_many_attempts`, which is terminal. The SDK never counts attempts locally; the server decides. |
| `Failed(sdk=Transport)` | Point **Custom URL** at a port with nothing listening. |
| `Failed(sdk=AlreadyRunning)` | Tap **Debug: collect states a second time**. Shown in the log and in a banner under that button — deliberately *not* in the main state display, since the real verification is unaffected. |
| `Failed(sdk=Superseded)` | Tap **Resend**. The old handle reports this before the new create is issued. |
| `Denied` | An application whose request callback refuses the start (`denied_by_callback`) or answers with something unusable (`denied_invalid_callback_response`). |
| `SetupError` | An application with **no `callback_url`**, used with Application-key auth (`denied_missing_callback_url`). |
| `Expired` | Wait out the countdown. The deadline is the server's, and arrives as `expires_at`. |
| `Captured` | Send a real SMS to a handset running this app, against a backend that echoes the app hash. See below. |

Two of these are easy to get backwards, so to be explicit: **no `callback_url` produces
`SetupError`, not `Denied`**, and an **unknown or malformed application key produces
`Failed(api=…)`, not `SetupError`**. The mapping lives in
`RealVerificationHandle.terminalStateFor`.

### When `Captured` appears

`Captured` is emitted when the SMS Retriever hands the code over without the user typing it.
The SDK sends this app's hash on every SMS verification and arms the Retriever only when the
create response echoes that same hash back. So whether `Captured` is reachable is decided by the
backend you point the demo at, per verification — not by anything in this app or the SDK.

To see it, you need all of:

- a **real handset** with Google Play services (an emulator image without them cannot receive the
  Retriever broadcast),
- a **real SMS** actually delivered — the raw state log will show the create response's `sms`
  block, and an `app_hash` in it that matches [§7](#7-the-sms-retriever-app-hash) means the
  message will carry the hash,
- the app **installed from the same build** whose hash you read in §7. Re-signing changes the
  hash.

If the response carries no `app_hash`, the Retriever is never armed, Play services is never
touched, and the code is typed by hand. That is not a failure — manual entry is live from the
first moment either way.

### Resend is a new verification

There is no resend endpoint. **Resend** calls `start()` again, which creates a *new*
verification and supersedes the previous one. The old generation normally reports
`Failed(sdk=Superseded)` in the log, then the new generation begins.

"Normally", not always: the supersede signal is delivered on a conflated channel that the old
handle only reads while it is *waiting for input*. A handle that is mid-submission when you tap
Resend reports whatever the server answered instead, and one that has already finished reports
nothing at all. Both are correct behaviour, not a lost signal.

## 7. The SMS Retriever app hash

The configuration screen shows the 11-character hash an SMS must end with before Android will
hand it to this app with no permission and no consent dialog.

It is computed **in this sample** (`AppSignature.kt`), reproducing the SDK's internal
computation byte for byte. Google publishes the same algorithm as `AppSignatureHelper` for apps
to copy, and the convention is to keep that copy in the sample rather than in the library — so
the SDK's own `AppHash` stays `internal`.

The value derives from the package name **and the signing certificate**, so it changes whenever
the signing key changes. A build signed with a real release key produces a different hash from a
debug-signed one. *This sample signs its release variant with the debug key, so both of its
variants happen to agree* — do not read that as a general rule. It does not vary by operating
system or by device: the same source, built anywhere with the same key, yields the same hash.

The SDK sends this value on every SMS verification, computed the same way, and the create
response echoes back whatever was stored against the verification. It is shown here because SMS
Retriever failure is completely silent on the device: a wrong hash means the message is simply
never delivered to the app, with no error, no callback and no log line. So when auto-capture does
not fire, the first thing to check is whether the hash on this screen matches the `app_hash` in
the raw state log's create response.

## 8. Known build warnings

`./gradlew :sample:assembleDebug` and `:assembleRelease` print, repeatedly:

```
WARNING: D8: An error occurred when parsing kotlin metadata. This normally happens when using
a newer version of kotlin than the kotlin version released when this version of R8 was created.
```

This is expected and harmless, on both operating systems. Kotlin 2.4.10 (July 2026) is newer
than the D8/R8 that ships inside AGP 8.13.2, so R8 cannot rewrite Kotlin's metadata. Dexing,
shrinking and the app itself are unaffected — a full verification completes on the minified
release build. Silencing it would mean bumping AGP, which the pinned toolchain deliberately
does not do.

## 9. What is verified on which platform

Stated because the rest of this file reads with equal confidence throughout, and it should
not. The same distinction governs the build guards, each of which was deliberately broken to
watch it fail: a claim nobody demonstrated is worth less than one somebody did.

| | macOS (arm64) | Linux (x86_64) |
|---|---|---|
| `./gradlew check assembleRelease`, five guards, 98 tests | run | run |
| `./gradlew --offline test` with no network interface at all | — | run |
| First build on a machine that has never built this | — | run |
| `tools/android-env.sh`, including both failure paths | run, bash + zsh | run, bash |
| `avdmanager create avd` producing the documented AVD | run | — |
| Emulator boot, `installDebug`, the app launching and rendering | run | — |
| A verification end to end against a running backend | run | — |
| A real handset over USB | — | — |

The Linux runs were done in a clean `x86_64` container, which is what makes "a machine that
has never built this" mean anything, and also why the emulator is absent from that column: it
needs `/dev/kvm`, and a container generally does not have it.

**The real-device path in [§2b](#2b-a-real-device) and [§4](#4-point-it-at-a-backend) is
reasoned, not demonstrated.** The `adb reverse` route works because `localhost` was already in
`network_security_config.xml`, and nothing in the app or the SDK distinguishes an emulator
from a handset — but nobody has plugged a phone in yet. Treat that row as the one to be
sceptical of, and correct this table when you do.

## 10. Deliberately not here

- **No tests.** The demo has none by design; testing a demo tests the demo. The SDK carries 98
  of its own, run by `./gradlew test`.
- **No login screen.** The iOS demo has a cosmetic one to show a real app's arc; this is a test
  tool.
- **No encrypted credential storage.** The Android analogue of the iOS Keychain,
  `androidx.security:security-crypto`, is deprecated and unmaintained.
- **No dependency on anything `internal`.** The demo adds no public SDK API, and
  `./gradlew check` verifies every `api/*.api` dump is unchanged.
