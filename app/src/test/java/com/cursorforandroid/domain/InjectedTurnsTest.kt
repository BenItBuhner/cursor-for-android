package com.cursorforandroid.domain

import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The turns Cursor injects into a coordinator's conversation, from the real transcript of this repository's own
 * Project coordinator (see [CoordinatorFixtures]): the `<user_query>` that follows a subagent's report is Cursor's
 * instruction to the model under either of its templates and never a prompt bubble; the titles' HTML entities read
 * as words; a subscribed pull request's change is a row made from its attributes; and the user's own words in the
 * same turn still render as theirs.
 */
class InjectedTurnsTest {

    private fun parse(name: String) = SystemNotifications.parse("m1", CoordinatorFixtures.injectedTurn(name), 1_789_341_000_000L)!!

    @Test
    fun `the follow-up instruction under the newer template is hidden, the subagent's row stays`() {
        val injected = parse("subagent_completion_necessary_follow_up")
        assertThat(injected.prompt).isNull()
        val row = injected.notifications.single()
        assertThat(row.kind).isEqualTo(SystemNotification.Kind.Subagent)
        assertThat(row.title).isEqualTo("Subagent completed")
        assertThat(row.summary).isEqualTo("Land merge train and prep release")
        assertThat(row.tone).isEqualTo(NoticeTone.Success)
        assertThat(row.agentId).isEqualTo("bc-24e35e9f-1a2d-5218-8e2e-7464ea74f671")
        // The report, without Cursor's framing sentence and resume hint.
        assertThat(row.body).isEqualTo("The merge train landed: #113 merged, v0.3.4 tagged, main bumped to 0.3.5. Release notes are in the PR body.")
        assertThat(row.timestampMillis).isEqualTo(1_789_341_000_000L)
    }

    @Test
    fun `the follow-up instruction under the older template is hidden too`() {
        val injected = parse("subagent_completion_already_visible")
        assertThat(injected.prompt).isNull()
        assertThat(injected.notifications.single().summary).isEqualTo("Build Projects under Extended mode")
    }

    @Test
    fun `both templates are the instruction, sentence by sentence, and a user's words are not`() {
        val newer = "Perform any necessary follow-up actions in response to the subagent completion above. If no follow-up work is needed, no further action is required. If you mention an agent or subagent in your response, link it with the `[Name](id)` Don't use generic label such as `[agent]`, `[worker]`, or `[subagent]`. Don't repeat the same confirmation every time."
        val older = "The beginning of the above subagent result is already visible to the user. Perform any follow-up actions (if needed). DO NOT regurgitate or reiterate its result unless asked."
        assertThat(SystemNotifications.isInstruction(newer)).isTrue()
        assertThat(SystemNotifications.isInstruction(older)).isTrue()
        // Each template's sentences, taken two at a time or one unmistakable one, still make the instruction.
        assertThat(SystemNotifications.isInstruction("Perform any necessary follow-up actions in response to the worker completion above. If no follow-up work is needed, no further action is required.")).isTrue()
        assertThat(SystemNotifications.isInstruction("If you were already aware, ignore this notification and do not restate prior responses.")).isTrue()
        // What Bennett would type.
        assertThat(SystemNotifications.isInstruction("Hold the release until I've checked the keyboard fix on my phone.")).isFalse()
        assertThat(SystemNotifications.isInstruction("Perform any follow-up actions the release needs.")).isFalse()
        assertThat(SystemNotifications.isInstruction("No further action is required from me, go ahead.")).isFalse()
    }

    @Test
    fun `the user's own words after a report render as the user's, the report as its row`() {
        val injected = parse("subagent_completion_with_users_own_words")
        assertThat(injected.notifications.single().title).isEqualTo("Subagent completed")
        assertThat(injected.prompt!!.text).isEqualTo("Hold the release until I've checked the keyboard fix on my phone.")
        assertThat(injected.prompt!!.id).isEqualTo("m1-prompt")
        assertThat(injected.items.map { it::class.simpleName }).containsExactly("SystemNotification", "UserMessage").inOrder()
    }

    @Test
    fun `HTML entities in a report's title and body read as the characters they stand for`() {
        val row = parse("subagent_completion_html_entities").notifications.single()
        assertThat(row.summary).isEqualTo("Hand & Arm Renders")
        assertThat(row.body).isEqualTo("Rendered the hand & arm stills into demo/ <4k>; PR opened.")
        assertThat(SystemNotifications.unescape("Tom &amp; Jerry &lt;3 &quot;quoted&quot; &#39;single&#39; &#x2F;slash &unknown; &")).isEqualTo("Tom & Jerry <3 \"quoted\" 'single' /slash &unknown; &")
    }

    @Test
    fun `a subscribed pull request's change is one row made from its attributes, with the link behind it`() {
        val synced = parse("github_pull_request_synchronize").notifications.single()
        assertThat(synced.kind).isEqualTo(SystemNotification.Kind.Other)
        assertThat(synced.title).isEqualTo("GitHub notification")
        assertThat(synced.summary).isEqualTo("#59 · synchronize · cursor[bot]")
        assertThat(synced.body).isEqualTo("A subscribed pull request changed. Use the linked PR for details if needed.\n\nhttps://github.com/BenItBuhner/cursor-for-android/pull/59")
        assertThat(parse("github_pull_request_synchronize").prompt).isNull()
        val merged = parse("github_pull_request_merged").notifications.single()
        assertThat(merged.summary).isEqualTo("#59 · merged · cursor[bot]")
    }

    @Test
    fun `a timer's notice is a row in the timer's name`() {
        val injected = SystemNotifications.parse(
            "t1",
            "<system_notification source=\"timer\">\nRender fan-out check-in 4: (a) kitchen: check /cursor/stores/bc-…/media\n</system_notification>",
        )!!
        val row = injected.notifications.single()
        assertThat(row.title).isEqualTo("Timer notification")
        assertThat(row.summary).isEqualTo("Render fan-out check-in 4: (a) kitchen: check /cursor/stores/bc-…/media")
        assertThat(injected.prompt).isNull()
    }
}
