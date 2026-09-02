package com.didww.android.sdk.verification.sample

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Base64
import java.security.MessageDigest

/**
 * The SMS Retriever app hash for the installed APK, computed **here in the sample**.
 *
 * ### Why this is duplicated rather than called
 *
 * The SDK's own `AppHash` is `internal`, and it stays that way: it is an implementation
 * detail of the SMS channel, not something a host has any reason to call. Google publishes
 * this same computation as `AppSignatureHelper` for apps to copy, and the convention is to
 * keep that copy in the sample rather than in the library. This file is that copy.
 *
 * It is deliberately byte-identical in behaviour to `AppHash.hash`: `SHA-256(packageName +
 * " " + signature)`, first nine bytes, base64 with no padding and no wrapping, first eleven
 * characters. If the two ever disagree, the value on screen would be worse than no value at
 * all — it would send someone hunting a backend bug that does not exist.
 *
 * ### Why show it at all
 *
 * SMS Retriever failure is completely silent on the device: a wrong hash means the message
 * is never handed to the app, with no error, no callback and no log. The usual cause is a
 * hash computed for a different signing certificate than the installed APK carries — which
 * is exactly what happens when a debug build is compared against a release one. Seeing the
 * value the running APK actually produces is the only way to check that from the outside.
 */
object AppSignature {

    private const val HASHED_BYTES = 9
    private const val BASE64_CHARS = 11

    /** `null` when the signing certificate cannot be read at all. */
    fun compute(context: Context): String? {
        val packageName = context.packageName
        val signature = currentSigner(context, packageName) ?: return null
        return hash(packageName, signature.toCharsString())
    }

    fun hash(packageName: String, signatureHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$packageName $signatureHex".toByteArray(Charsets.UTF_8))
        return Base64
            .encodeToString(digest.copyOf(HASHED_BYTES), Base64.NO_PADDING or Base64.NO_WRAP)
            .take(BASE64_CHARS)
    }

    /**
     * The certificate the APK is *currently* signed with. With signing-certificate
     * rotation `signingCertificateHistory` holds the original first and the current one
     * last, so taking the first entry would hash a certificate that no longer signs
     * anything. `signingInfo` is genuinely null in practice, so the deprecated array is
     * kept as a fallback at every API level rather than only below 28.
     */
    @Suppress("DEPRECATION")
    private fun currentSigner(context: Context, packageName: String): Signature? = runCatching {
        val pm = context.packageManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            val current = info?.apkContentsSigners?.firstOrNull()
                ?: info?.signingCertificateHistory?.lastOrNull()
            if (current != null) return@runCatching current
        }

        pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    }.getOrNull()
}
