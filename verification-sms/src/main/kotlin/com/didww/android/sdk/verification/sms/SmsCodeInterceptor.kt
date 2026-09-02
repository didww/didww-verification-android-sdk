package com.didww.android.sdk.verification.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Arms the SMS Retriever and emits codes extracted from messages it hands over.
 *
 * Never touches Play Services until it is collected, and it is only ever collected when
 * the server has signalled that auto-capture will work for this verification.
 */
internal class SmsCodeInterceptor(
    private val context: Context,
    private val template: String?,
) {

    fun codes(): Flow<String> = callbackFlow {
        // Teardowns run in reverse registration order, and run exactly once whether the
        // producer body throws part-way through or the flow is cancelled normally.
        //
        // This matters more than it looks: if registerReceiver succeeds and
        // startSmsRetriever then throws, `awaitClose` is never reached — the producer
        // died before suspending — and the receiver leaks for the whole process lifetime.
        val teardowns = ArrayDeque<() -> Unit>()
        var tornDown = false
        fun tearDown() {
            if (tornDown) return
            tornDown = true
            // A `for` loop, not `teardowns.forEach {}`: the latter resolves to
            // java.lang.Iterable#forEach, which is API 24, and this SDK's floor is 23.
            // Every teardown runs even if an earlier one throws — a failure to unregister
            // one thing must not leak the rest.
            for (teardown in teardowns) runCatching(teardown)
        }

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                    handle(intent.extras)
                }
            }

            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                // The sender permission is not optional. A runtime-exported receiver is
                // reachable by every app on the device and the Retriever's extras are
                // trivially forgeable, so without this guard a hostile app can inject a
                // code that this SDK then submits on the user's behalf.
                //
                // ContextCompat.registerReceiver has a 4-arg and a 6-arg overload and NO
                // 5-arg form. The 4-arg one compiles cleanly and silently drops the
                // permission — do not "fix" a signature error by reaching for it.
                SmsRetriever.SEND_PERMISSION,
                null,
                ContextCompat.RECEIVER_EXPORTED,
            )
            teardowns.addFirst { context.unregisterReceiver(receiver) }

            arm()
        } catch (t: Throwable) {
            tearDown()
            throw t
        }

        awaitClose { tearDown() }
    }

    private fun arm() {
        SmsRetriever.getClient(context).startSmsRetriever()
    }

    private fun kotlinx.coroutines.channels.ProducerScope<String>.handle(extras: Bundle?) {
        @Suppress("DEPRECATION")
        val status = extras?.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return

        when (status.statusCode) {
            CommonStatusCodes.SUCCESS -> {
                @Suppress("DEPRECATION")
                val body = extras.get(SmsRetriever.EXTRA_SMS_MESSAGE) as? String ?: return
                SmsCodeExtractor.extract(template, body)?.let { trySend(it) }
            }

            CommonStatusCodes.TIMEOUT -> {
                // Play Services' listener window is a hard five minutes, which is Google's
                // number and may be shorter than the server's deadline. Re-arm rather than
                // introducing a client-side interception timeout of our own.
                //
                // Deliberately unconditional: this flow is cancelled the moment the
                // verification reaches a terminal state or its deadline passes, so the
                // orchestrator already bounds it. Re-checking the deadline here would mean
                // duplicating the countdown into a second place that could disagree.
                Log.d(LOG_TAG, "SMS Retriever window elapsed; re-arming")
                runCatching { arm() }
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "DidwwVerification"
    }
}
