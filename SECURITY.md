# Security policy

## Reporting a vulnerability

Use GitHub's **[private vulnerability reporting](https://github.com/didww/didww-verification-android-sdk/security/advisories/new)**
— *Security* → *Report a vulnerability* on this repository. It opens a channel visible only to
the maintainers. Please do not open a public issue for a suspected vulnerability.

Include the SDK version, the module, and enough detail to reproduce. If you have a proof of
concept, a failing test against this repository is the most useful form.

If the issue is in the DIDWW verification API rather than in this client, raise it through your
DIDWW account instead — it is fixed server-side and needs no SDK release.

## Supported versions

Only the latest release is supported. There is no backporting.

## Things that are working as intended

Two behaviours look like findings and are not. Both are documented where they occur; they are
repeated here because this is the page a reader checks first.

### `Auth.Basic` puts a server-to-server secret in your app

`Auth.Basic(key, secret)` sends `Authorization: Basic base64(key:secret)`. **The secret is
recoverable from any APK that contains it.** It exists for local development and for hosts
that proxy through their own backend, and the SDK logs a warning at runtime when it is used
in a build the host has not marked debuggable.

Ship `Auth.Public(applicationKey)` instead. The application key identifies your
application but is not a secret: extracting it from an APK gains an attacker nothing they
could not do by installing the app, because the server asks your application's
`callback_url` to authorise each verification.

If you have shipped `Auth.Basic` in a released app, treat the secret as disclosed and rotate
it.

### The SMS broadcast receiver is registered as exported

`verification-sms` registers a runtime receiver for the SMS Retriever's broadcast, which
Android requires to be exported because the sender is Google Play services. It is registered
with `SmsRetriever.SEND_PERMISSION`, so only Play services can deliver to it. Without that
permission any app on the device could forge the Retriever's extras and inject a code this
SDK would then submit on the user's behalf — the guard is deliberate and load-bearing.

## What this SDK does not do

- **It stores nothing.** No credential, code, or verification state is persisted — the SDK
  opens no file and writes no `SharedPreferences`. (The `:sample` app in this repository does
  save its own settings, in plain `SharedPreferences`, and is not part of any published
  artifact.)
- **It logs no code, no destination number, and no credential.** There are four log calls in
  the whole SDK: the `Auth.Basic` warning above, two in the SMS app-hash computation (your own
  package name, and the app hash itself at `DEBUG` — that hash is appended to every message
  and is derived from public inputs), and one noting that the SMS Retriever's listening window
  elapsed.
- **It asks for exactly one permission: `INTERNET`.** The full merged-manifest cost, including
  what arrives transitively through Play services, is measured in `docs/manifest-cost.md` and
  frozen by a check.
