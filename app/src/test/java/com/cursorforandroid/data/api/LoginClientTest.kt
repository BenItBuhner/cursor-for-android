package com.cursorforandroid.data.api

import com.cursorforandroid.BuildConfig
import com.google.common.truth.Truth.assertThat
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Test

/**
 * `/auth/poll` carries the PKCE verifier that redeems a sign-in — in its body, and in the query string of the GET
 * an older backend falls back to. Its client must therefore log nothing at all, on every build: even
 * [HttpLoggingInterceptor.Level.BASIC] writes the request line, and that is the URL.
 */
class LoginClientTest {

    @Test
    fun `the login client logs nothing on the build where the others do`() {
        // These tests run against the debug build, the one every other client does get a logger on.
        assertThat(BuildConfig.DEBUG).isTrue()
        assertThat(CursorApiFactory.okHttp { null }.interceptors.filterIsInstance<HttpLoggingInterceptor>()).isNotEmpty()

        val login = CursorApiFactory.loginClient()

        assertThat(login.interceptors.filterIsInstance<HttpLoggingInterceptor>()).isEmpty()
        assertThat(login.networkInterceptors).isEmpty()
    }
}
