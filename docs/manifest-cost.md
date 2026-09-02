# What an integrator's manifest inherits

Measured, not predicted. Method: `:manifest-probe` is an application module that
declares nothing and depends only on `:verification-all`; its MERGED release manifest
is therefore exactly the SDK's manifest cost. Control: the same module with the
`implementation(project(":verification-all"))` line removed merges **nothing at all** —
so every element below is attributable to this SDK, not to AGP's application baseline.

Measured 2026-07-31 against AGP 8.13.2, play-services-auth-api-phone 18.3.1,
androidx.core 1.16.0. Re-measure on every dependency bump; the golden enforces it.

| Element | `android:name` | Contributed by |
|---|---|---|
| `uses-permission` | `android.permission.INTERNET` | **us** (verification-core) — intended |
| `permission` | `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature) | androidx.core |
| `uses-permission` | `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core |
| `application[appComponentFactory]` | `androidx.core.app.CoreComponentFactory` | androidx.core |
| `activity` | `com.google.android.gms.common.api.GoogleApiActivity` | play-services-base |
| `meta-data` | `com.google.android.gms.version` | play-services-basement |
| `provider` | `androidx.startup.InitializationProvider` | androidx.profileinstaller |
| `receiver` | `androidx.profileinstaller.ProfileInstallReceiver` (**exported**, guarded by `android.permission.DUMP`) | androidx.profileinstaller |

## The provider and the receiver are Play Services' fault, not androidx.core's

    verification-sms
      -> play-services-auth-api-phone:18.3.1
        -> play-services-base:18.5.0
          -> androidx.fragment:1.1.0
            -> androidx.activity:1.0.0
              -> androidx.lifecycle-runtime:2.6.2
                -> androidx.profileinstaller:1.3.0
                  -> androidx.startup:startup-runtime:1.1.1

Verified independent of our androidx.core pin: 1.13.1 and 1.16.0 produce byte-identical
merged manifests, so bumping or pinning androidx.core does not remove any of this.

## Consequences

1. **This cannot be predicted from the AARs.** Reading them in isolation misses what the
   manifest merger contributes: the `permission` pair, `appComponentFactory`, the `provider`
   and an **exported** `receiver` all appear only in a real merge. The golden is built from the
   measured output above, and re-measured rather than re-reasoned.
2. **This is the strongest argument for keeping `verification-sms` separate.** A host that uses
   only callout avoids not merely "Play Services" but a content provider, an exported broadcast
   receiver, and the whole androidx.fragment/activity/lifecycle chain. It is also why callout
   was *not* given a coordinate of its own: it contributes not a single element to this table,
   so splitting it would have divided the API without dividing any cost.
3. **"One permission" is true and is not the whole story.** The SDK's *permission* cost is
   exactly INTERNET, as promised. Its *manifest* cost is not, and an integrator auditing a
   merged manifest deserves to see the difference stated rather than discover it.
