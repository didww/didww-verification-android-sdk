# verification-sms

SMS verification.

```kotlin
val handle = SmsVerification(context, Auth.Public(APP_KEY))
    .start("+37112345678", SmsOptions(languages = listOf("en-US")))
handle.submit(codeTheUserTyped)   // valid from t=0
```

## What this module costs you

This is the only module in the SDK that pulls Google Play Services, and it does so from day
one — including for verifications that never end up capturing anything. That is a deliberate
trade and you should know the size of it before adopting.

Measured against an application module that declares nothing (`:manifest-probe`, and verified
against a control build with the dependency removed), depending on this module adds to your
**merged manifest**:

| Element | `android:name` | Arrives via |
|---|---|---|
| `uses-permission` | `android.permission.INTERNET` | this SDK — the only permission we ask for |
| `permission` + `uses-permission` | `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature-level) | androidx.core |
| `application[appComponentFactory]` | `androidx.core.app.CoreComponentFactory` | androidx.core |
| `activity` | `com.google.android.gms.common.api.GoogleApiActivity` | play-services-base |
| `meta-data` | `com.google.android.gms.version` | play-services-basement |
| `provider` | `androidx.startup.InitializationProvider` | androidx.profileinstaller |
| `receiver` | `androidx.profileinstaller.ProfileInstallReceiver` (**exported**, guarded by `android.permission.DUMP`) | androidx.profileinstaller |

The provider and the exported receiver are the ones worth noticing. They arrive through a
long transitive chain that starts at Play Services:

```
play-services-auth-api-phone -> play-services-base -> androidx.fragment
  -> androidx.activity -> androidx.lifecycle-runtime -> profileinstaller -> androidx.startup
```

None of it is removable by pinning `androidx.core` — 1.13.1 and 1.16.0 produce byte-identical
merged manifests. **If you only need callout verification, depend on `verification-core`
directly and none of it applies to you except `INTERNET`** — that channel lives there, and
`INTERNET` is the only permission this SDK ever asks for. That is precisely why this SDK ships
as three artifacts instead of one, and why the one boundary it splits on is this module.

`compileOnly` was considered and rejected: the Retriever call has to work at runtime the moment
the server starts echoing the hash, and `compileOnly` would turn that into a
`NoClassDefFoundError` in every host that had not independently added Play Services.

## Automatic capture is switched on from the server, per verification

The SDK computes the SMS Retriever app hash and sends it on **every** verification, whether
or not the backend can use it. It arms the Retriever only when the create response echoes
back the exact hash it sent.

Where there is no echo, `SmsRetriever` is never called, no receiver is registered, and Play
Services is never touched — asserted in the default test run, not merely intended. Where there
is, **every copy of this SDK already installed on every handset captures codes automatically**
— no SDK release, no recompile on your side, no coordination.

The echo must be the *stored* hash rather than a "we support this" flag. Rendering the
response and dispatching the message are separate concerns on the server side, so a
capability flag could truthfully report support while the component that appends the hash did
not yet do so — and the Retriever would be armed for a message that could never reach it.
Comparing the stored value makes "echoed and equal" mean "it will be appended", because it is
the same datum rather than merely the same version.

Manual entry works throughout and is unaffected.

### You never configure the app hash

This is the usual place SMS Retriever integrations go wrong, and it is a mistake this SDK
cannot make on your behalf: the hash is computed at runtime from the **certificate the
installed APK is actually signed with**, which is exactly what Play services matches against.

So Play App Signing needs nothing from you. An app uploaded with your upload key and re-signed
by Google is signed, on the device, with Google's key — and that is the certificate the SDK
reads. There is no key to copy into a build config, no value to keep in step with a release,
and no upload-key/app-signing-key confusion to fall into. Re-signing an app simply changes the
hash, and the next verification sends the new one.

Note what the echo does and does not prove. It proves the server stored the value this client
sent; it is not a check that the value is *right*. Correctness comes from computing the hash
locally, from the running APK.

### How long the listener stays armed

`AwaitingInput.sms.interceptionTimeoutSeconds` is how long the server thinks an on-device
listener is worth keeping armed. The SDK stops listening at that point or when the verification
ends, whichever is sooner. Running out **stops capture and nothing else** — the verification is
still live, manual entry still works, and `expires_at` is still the only deadline.

### A message that arrives too early is missed

The capability signal is *in* the create response, so there is nothing to arm before it. A
message delivered before that response is decoded is not captured automatically — the Retriever
only hands over messages received while it is armed. It is rare, and the fallback is the path
that always works: the user types the code.

### Devices without Play Services

`startSmsRetriever` cannot work on a device with no Play Services. Manual entry remains
available and is the fallback; nothing in the flow depends on capture succeeding.
