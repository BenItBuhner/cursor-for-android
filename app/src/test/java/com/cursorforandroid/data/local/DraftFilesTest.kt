package com.cursorforandroid.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cursorforandroid.domain.CursorUser
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Parking a signed-out account's drafts and handing them back: nothing is written over, and nothing is lost. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DraftFilesTest {

    private val filesDir: File = ApplicationProvider.getApplicationContext<Context>().filesDir
    private val roots = listOf(DraftFiles.Root("followups", entryWise = true), DraftFiles.Root("draft", entryWise = false))

    @After
    fun tearDown() {
        listOf("followups", "draft", DraftFiles.PARKED_DIR).forEach { File(filesDir, it).deleteRecursively() }
    }

    private fun file(path: String, text: String) = File(filesDir, path).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun text(path: String): String? = File(filesDir, path).takeIf { it.isFile }?.readText()

    @Test
    fun `an account's drafts are parked whole and come back whole`() {
        file("followups/bc-1/state.json", "chat one")
        file("followups/bc-2/state.json", "chat two")
        file("draft/composer.json", "new chat")
        file("draft/1f2e", "image")

        DraftFiles.park(filesDir, "owner-a", roots)

        assertThat(File(filesDir, "followups").exists()).isFalse()
        assertThat(File(filesDir, "draft").exists()).isFalse()

        DraftFiles.unpark(filesDir, "owner-a", roots)

        assertThat(text("followups/bc-1/state.json")).isEqualTo("chat one")
        assertThat(text("followups/bc-2/state.json")).isEqualTo("chat two")
        assertThat(text("draft/composer.json")).isEqualTo("new chat")
        assertThat(text("draft/1f2e")).isEqualTo("image")
        assertThat(File(filesDir, "${DraftFiles.PARKED_DIR}/owner-a").exists()).isFalse()
    }

    @Test
    fun `another account's sign-in finds nothing, and the parked drafts wait for their own`() {
        file("followups/bc-1/state.json", "A's draft")
        DraftFiles.park(filesDir, "owner-a", roots)

        DraftFiles.unpark(filesDir, "owner-b", roots)
        assertThat(text("followups/bc-1/state.json")).isNull()

        DraftFiles.unpark(filesDir, "owner-a", roots)
        assertThat(text("followups/bc-1/state.json")).isEqualTo("A's draft")
    }

    @Test
    fun `a record whose place was taken meanwhile stays parked rather than being written over`() {
        file("followups/bc-1/state.json", "parked")
        file("draft/composer.json", "parked new chat")
        DraftFiles.park(filesDir, "owner-a", roots)
        file("followups/bc-1/state.json", "typed since")
        file("followups/bc-2/state.json", "also typed since")
        file("draft/composer.json", "typed since")

        DraftFiles.unpark(filesDir, "owner-a", roots)

        assertThat(text("followups/bc-1/state.json")).isEqualTo("typed since")
        assertThat(text("draft/composer.json")).isEqualTo("typed since")
        assertThat(text("${DraftFiles.PARKED_DIR}/owner-a/followups/bc-1/state.json")).isEqualTo("parked")
        assertThat(text("${DraftFiles.PARKED_DIR}/owner-a/draft/composer.json")).isEqualTo("parked new chat")

        // Parked again over what is still parked: the older copy is kept beside the newer one.
        DraftFiles.park(filesDir, "owner-a", roots)
        val parkedChats = File(filesDir, "${DraftFiles.PARKED_DIR}/owner-a/followups").list()!!.toList()
        assertThat(parkedChats).containsAtLeast("bc-1", "bc-2")
        assertThat(parkedChats.single { it.startsWith("bc-1.superseded-") }).isNotEmpty()
        assertThat(text("${DraftFiles.PARKED_DIR}/owner-a/followups/bc-1/state.json")).isEqualTo("typed since")
    }

    @Test
    fun `an account is named by its id, else its email, never in the clear`() {
        val byId = DraftFiles.ownerKey(CursorUser("key", "Bennett@Example.com", null, null, 7L))
        val sameIdOtherEmail = DraftFiles.ownerKey(CursorUser("key", "other@example.com", null, null, 7L))
        val byEmail = DraftFiles.ownerKey(CursorUser("key", " bennett@example.com ", null, null, null))
        val byEmailCased = DraftFiles.ownerKey(CursorUser("key", "Bennett@Example.com", null, null, null))

        assertThat(byId).isEqualTo(sameIdOtherEmail)
        assertThat(byEmail).isEqualTo(byEmailCased)
        assertThat(byEmail).isNotEqualTo(byId)
        // A digest, and nothing else: no id, no address.
        assertThat(byId).matches("[0-9a-f]{24}")
        assertThat(byEmail).matches("[0-9a-f]{24}")
        assertThat(DraftFiles.ownerKey(CursorUser("Cursor", null, null, null, null))).isNull()
    }

    @Test
    fun `a record is replaced whole and a write that never finished leaves the previous one readable`() {
        val target = File(filesDir, "draft/composer.json")
        DraftFiles.write(target, "first")
        // A write that died before its rename: its bytes sit beside the record, never read as it.
        File(filesDir, "draft/composer.json.new").writeText("half")

        assertThat(DraftFiles.read(target)).isEqualTo("first")
        DraftFiles.write(target, "second")
        assertThat(DraftFiles.read(target)).isEqualTo("second")
        assertThat(File(filesDir, "draft").list()!!.toList()).containsExactly("composer.json")
    }
}
