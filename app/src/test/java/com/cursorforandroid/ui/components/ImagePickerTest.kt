package com.cursorforandroid.ui.components

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * The conversation screen recomposes on every streamed delta, and the photo picker is remembered inside it. A
 * contract without equals re-keys rememberLauncherForActivityResult's DisposableEffect, which unregisters and
 * re-registers the launcher — dropping any pending result bundled under the old key with it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImagePickerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
    }

    /** The request codes the registry currently holds; a re-registration mints a new one for the same key. */
    private fun registeredCodes(): List<Int> {
        val bundle = Bundle()
        registry.onSaveInstanceState(bundle)
        return bundle.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS").orEmpty()
    }

    @Test
    fun `the picker is registered once, not once per recomposition`() {
        var tick by mutableIntStateOf(0)
        compose.setContent {
            val owner = remember { object : ActivityResultRegistryOwner { override val activityResultRegistry = registry } }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                // Read the counter so the composable that owns the launcher really does recompose.
                @Suppress("UNUSED_EXPRESSION")
                tick
                rememberImagePicker(currentCount = 0, onPicked = {}, onError = {})
            }
        }
        compose.waitForIdle()
        // One launcher for a multi-image pick, one for the last free slot.
        val initial = registeredCodes()
        assertThat(initial).hasSize(2)

        repeat(20) {
            compose.runOnIdle { tick++ }
            compose.waitForIdle()
        }
        assertThat(registeredCodes()).isEqualTo(initial)
    }

    @Test
    fun `filling up the attachment slots re-registers, because the picker's limit changed`() {
        var attached by mutableIntStateOf(0)
        compose.setContent {
            val owner = remember { object : ActivityResultRegistryOwner { override val activityResultRegistry = registry } }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                rememberImagePicker(currentCount = attached, onPicked = {}, onError = {})
            }
        }
        compose.waitForIdle()
        val initial = registeredCodes()

        compose.runOnIdle { attached = 3 }
        compose.waitForIdle()
        assertThat(registeredCodes()).isNotEqualTo(initial)
    }
}
