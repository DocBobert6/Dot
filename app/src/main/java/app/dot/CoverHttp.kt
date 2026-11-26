package app.dot

import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL

// Copyright (c) 2026 Dot contributors. All rights reserved.
//
// Dot — one capped HTTP GET (SHELL-SPEC §8.3). Connect timeout
// 10 s, read timeout 15 s. A read past the cap fails with
// "over <cap>B cap". A 403 with `X-RateLimit-Remaining: 0` is
// reported as "HTTP 403 — rate limit — resets HH:mm:ss" (display
// reason only — never persisted). Transport failures are named by
// kind: "timed out" for a socket timeout, "cancelled" for a closed
// socket, else the exception's simple class name. The caller races
// the call against its own budget (a timeout cannot unwind the
// thread stuck on it, so the pipeline awaits a deferred with the
// budget and abandons the thread on timeout).

object CoverHttp {

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000
    const val USER_AGENT = "Dot"

    data class Result(val bytes: ByteArray?, val error: String?)

    /**
     * One GET, capped at [maxBytes]. The `Accept:
     * application/vnd.github+json` header is sent only to
     * api.github.com, never to raw.githubusercontent.com (§8.1).
     */
    fun get(url: String, maxBytes: Int): Result {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (url.contains("api.github.com")) {
                    connection.setRequestProperty(
                        "Accept",
                        "application/vnd.github+json",
                    )
                }
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    if (code == HttpURLConnection.HTTP_FORBIDDEN) {
                        val remaining = connection.getHeaderField(
                            "X-RateLimit-Remaining",
                        )
                        if (remaining == "0") {
                            val reset = connection.getHeaderField(
                                "X-RateLimit-Reset",
                            )?.toLongOrNull()
                            val whenText = if (reset != null) {
                                val sdf = java.text.SimpleDateFormat(
                                    "HH:mm:ss",
                                    java.util.Locale.US,
                                )
                                sdf.format(java.util.Date(reset * 1000))
                            } else {
                                "unknown"
                            }
                            return Result(
                                null,
                                "HTTP 403 — rate limit — resets $whenText",
                            )
                        }
                    }
                    return Result(null, "HTTP $code")
                }
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                connection.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > maxBytes) {
                            return Result(null, "over ${maxBytes}B cap")
                        }
                    }
                }
                Result(out.toByteArray(), null)
            } finally {
                connection.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            Result(null, "timed out")
        } catch (e: SocketException) {
            Result(null, "cancelled")
        } catch (e: Exception) {
            Result(null, e.javaClass.simpleName)
        }
    }
}
