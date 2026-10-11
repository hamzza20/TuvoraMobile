package com.nuvio.app.features.player

import com.nuvio.app.features.streams.PlaybackTlsClass
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import java.io.IOException
import javax.net.ssl.SSLException

/**
 * Strict TLS first, always. Only when the handshake FAILS (an [SSLException]) AND the request host is classified
 * [PlaybackTlsClass.PermissivePanel] (one of the user's IPTV panels/streams) is the same request retried on [permissive].
 * Every other host - media servers, add-ons, CDNs, anything unknown - gets the strict result, failures included.
 */
internal class PanelFallbackCallFactory(
    private val strict: OkHttpClient,
    private val permissive: OkHttpClient,
    private val classify: (host: String) -> PlaybackTlsClass,
) : Call.Factory {
    override fun newCall(request: Request): Call =
        FallbackCall(request, strict, permissive, classify(request.url.host) == PlaybackTlsClass.PermissivePanel, classify)

    private class FallbackCall(
        private val original: Request,
        private val strict: OkHttpClient,
        private val permissive: OkHttpClient,
        private val mayFallBack: Boolean,
        private val classify: (String) -> PlaybackTlsClass,
    ) : Call {
        @Volatile private var current: Call = strict.newCall(original)

        @Volatile private var canceled = false

        override fun request(): Request = original

        override fun execute(): Response = try {
            current.execute()
        } catch (error: SSLException) {
            if (!mayFallBack || canceled) throw error
            current = permissive.newCall(original)
            current.execute()
        }

        override fun enqueue(responseCallback: Callback) {
            current.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (e is SSLException && mayFallBack && !canceled) {
                        current = permissive.newCall(original)
                        current.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(this@FallbackCall, e)
                            override fun onResponse(call: Call, response: Response) = responseCallback.onResponse(this@FallbackCall, response)
                        })
                    } else {
                        responseCallback.onFailure(this@FallbackCall, e)
                    }
                }

                override fun onResponse(call: Call, response: Response) = responseCallback.onResponse(this@FallbackCall, response)
            })
        }

        override fun cancel() {
            canceled = true
            current.cancel()
        }

        override fun isExecuted(): Boolean = current.isExecuted()

        override fun isCanceled(): Boolean = canceled || current.isCanceled()

        override fun timeout(): Timeout = current.timeout()

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>): T? = current.tag(type)

        override fun <T> tag(type: Class<out T>): T? = current.tag(type)

        override fun <T : Any> tag(type: kotlin.reflect.KClass<T>, computeIfAbsent: () -> T): T = current.tag(type, computeIfAbsent)

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T = current.tag(type, computeIfAbsent)

        override fun clone(): Call = FallbackCall(original, strict, permissive, mayFallBack, classify)
    }
}
