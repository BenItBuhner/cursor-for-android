package com.cursorforandroid.domain

import com.cursorforandroid.BuildConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The versionCode scheme must agree with app/build.gradle.kts, which the examples here are taken from. */
class AppVersionTest {

    /**
     * The one assertion the examples below cannot make: that the build script derived this build's versionCode from
     * the same name the app reports. A CI build carries `-Papp.versionNameSuffix`, so this is where a code computed
     * from the base version rather than the resolved one shows up (0.2.0-dev.148 as 20099 instead of 20024).
     */
    @Test
    fun `the build's own versionCode is the one this scheme derives from its versionName`() {
        val version = AppVersion.parse(BuildConfig.VERSION_NAME)
        assertThat(version).isNotNull()
        assertThat(version!!.versionCode).isEqualTo(BuildConfig.VERSION_CODE)
    }

    /** Expected values are what `./gradlew -q :app:printAppVersion -Papp.versionName=…` prints for the same names. */
    @Test
    fun `version codes follow the build script's scheme`() {
        assertThat(AppVersion.parse("0.2.0-alpha.1")!!.versionCode).isEqualTo(20001)
        assertThat(AppVersion.parse("0.2.0-beta.1")!!.versionCode).isEqualTo(20026)
        assertThat(AppVersion.parse("0.2.0-rc.1")!!.versionCode).isEqualTo(20051)
        assertThat(AppVersion.parse("0.2.0")!!.versionCode).isEqualTo(20099)
        assertThat(AppVersion.parse("0.1.0")!!.versionCode).isEqualTo(10099) // the published v0.1.0 release's code
        assertThat(AppVersion.parse("0.2.1")!!.versionCode).isEqualTo(20199)
        assertThat(AppVersion.parse("1.12.34")!!.versionCode).isEqualTo(1_123_499)
    }

    @Test
    fun `pre-releases sort below their final build and CI dev builds below every release of the version`() {
        val dev = AppVersion.parse("0.2.0-dev.42+gabc1234")!!
        val rc = AppVersion.parse("0.2.0-rc.1")!!
        val stable = AppVersion.parse("0.2.0")!!
        val next = AppVersion.parse("0.2.1-alpha.0")!!
        assertThat(dev.versionCode).isEqualTo(20024) // dev.N counts as alpha; N is capped at 24
        assertThat(dev).isLessThan(rc)
        assertThat(rc).isLessThan(stable)
        assertThat(stable).isLessThan(next)
        assertThat(dev.build).isEqualTo("gabc1234")
        assertThat(dev.isPreRelease).isTrue()
        assertThat(stable.isPreRelease).isFalse()
    }

    @Test
    fun `stage iterations are capped so a runaway counter cannot cross into the next stage`() {
        assertThat(AppVersion.stage("alpha.99")).isEqualTo(24)
        assertThat(AppVersion.stage("beta.99")).isEqualTo(49)
        assertThat(AppVersion.stage("rc.99")).isEqualTo(98)
        assertThat(AppVersion.stage("rc")).isEqualTo(50)
        assertThat(AppVersion.stage("RC.2")).isEqualTo(52)
        assertThat(AppVersion.stage(null)).isEqualTo(99)
        assertThat(AppVersion.stage("")).isEqualTo(99)
    }

    @Test
    fun `tags may carry the leading v and render without it`() {
        val version = AppVersion.parse("v0.2.0-rc.1")!!
        assertThat(version).isEqualTo(AppVersion(0, 2, 0, "rc.1"))
        assertThat(version.toString()).isEqualTo("0.2.0-rc.1")
        assertThat(AppVersion.parse(" v1.0.0 ").toString()).isEqualTo("1.0.0")
        assertThat(AppVersion.parse("0.2.0+g1234").toString()).isEqualTo("0.2.0+g1234")
    }

    @Test
    fun `anything that is not a version, or does not fit the scheme, is rejected`() {
        listOf("", "latest", "1.2", "1.2.3.4", "v1.2.3-", "1.2.3 beta", "0.100.0", "0.0.100", "2000.0.0", "nightly-2026").forEach {
            assertThat(AppVersion.parse(it)).isNull()
        }
    }
}
