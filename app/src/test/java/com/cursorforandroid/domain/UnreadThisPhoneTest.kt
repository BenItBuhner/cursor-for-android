package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.ZoneOffset

/**
 * Settings › "Unread only for chats from this phone" (`LocalAgentState.unreadOnlyTouchedHere`): with it on, a chat
 * this phone never started or opened never reads as unread — not its own row, not a folded group's dot, not a
 * Project's badge, not the widget's rows — and a chat touched here reads exactly as it does with the switch off.
 * One account state throughout; only the switch and the phone's own record of what it touched change.
 */
class UnreadThisPhoneTest {

    private val zone = ZoneOffset.UTC
    private val now = 1_800_000_000_000L // 2027-01-15T08:00:00Z
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun agent(id: String, updatedAgo: Long, isProject: Boolean = false, parent: String? = null) = Agent(
        id = id,
        name = id,
        lifecycle = AgentLifecycle.IDLE,
        runStatus = RunStatus.FINISHED,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - updatedAgo - hour,
        updatedAtMillis = now - updatedAgo,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
        isProject = isProject,
        parent = parent?.let { AgentParent(it, AgentParentKind.PROJECT_WORKER) },
    )

    /** Started on the laptop and never touched here: unread today, as it has no read marker on this phone. */
    private val laptop = agent("laptop", updatedAgo = 1 * hour)
    /** Started on the laptop yesterday; a folded "Yesterday" group holds it alone. */
    private val laptopYesterday = agent("laptop-yesterday", updatedAgo = 1 * day + 2 * hour)
    /** Started on this phone; it finished after the read marker the launch wrote. */
    private val phone = agent("phone", updatedAgo = 2 * hour)
    /** Started on the laptop, opened here once, and active again since. */
    private val opened = agent("opened", updatedAgo = 3 * hour)
    /** A Project started on the laptop, with a worker; neither touched here. */
    private val project = agent("project", updatedAgo = 4 * hour, isProject = true)
    private val worker = agent("worker", updatedAgo = 5 * hour, parent = "project")
    /** Pinned on the account and never opened here. */
    private val pinnedLaptop = agent("pinned-laptop", updatedAgo = 6 * hour)

    private val agents = listOf(laptop, laptopYesterday, phone, opened, project, worker, pinnedLaptop)

    /** What this phone holds: its launch, its one open (both older than the chats' newest activity), a pin. */
    private fun local(switchOn: Boolean) = LocalAgentState(
        pinnedIds = setOf(pinnedLaptop.id),
        readMarkers = mapOf(phone.id to phone.listedAtMillis - hour, opened.id to opened.listedAtMillis - hour),
        launchedHereIds = setOf(phone.id),
        touchedHereIds = setOf(phone.id, opened.id),
        unreadOnlyTouchedHere = switchOn,
    )

    private fun sections(local: LocalAgentState, prefs: ListPreferences = ListPreferences()) =
        AgentListOrganizer.organize(agents, prefs, local, nowMillis = now, zone = zone)

    private fun List<AgentSection>.allRows(): List<AgentRow> = flatMap { section -> section.rows.flatMap { listOf(it) + it.descendants() } }

    private fun List<AgentSection>.unreadIds(): Set<String> = allRows().filter { it.isUnread }.mapTo(HashSet()) { it.agent.id }

    private fun widgetUnread(mode: WidgetMode, local: LocalAgentState): Set<String> =
        WidgetList.rows(mode, agents, ListPreferences(), local, nowMillis = now, zone = zone).filter { it.indicator == AgentIndicator.Unread }.mapTo(HashSet()) { it.agent.id }

    @Test
    fun `with the switch off every chat reads unread as it does today`() {
        val local = local(switchOn = false)
        val sections = sections(local)

        assertThat(sections.unreadIds()).containsExactly(laptop.id, laptopYesterday.id, phone.id, opened.id, project.id, worker.id, pinnedLaptop.id)
        // A folded "Yesterday" carries the dot for the laptop's chat; the Project wears its unread badge.
        assertThat(sections.first { it.key == "date:Yesterday" }.rows.any { it.isUnread }).isTrue()
        assertThat(sections.first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows.single().indicator).isEqualTo(AgentIndicator.Unread)
        assertThat(widgetUnread(WidgetMode.Recent, local)).containsExactly(laptop.id, laptopYesterday.id, phone.id, opened.id, pinnedLaptop.id)
        assertThat(widgetUnread(WidgetMode.Pinned, local)).containsExactly(pinnedLaptop.id)
    }

    @Test
    fun `with the switch on a chat this phone never touched never reads as unread, anywhere`() {
        val local = local(switchOn = true)
        val sections = sections(local)

        for (untouched in listOf(laptop, laptopYesterday, project, worker, pinnedLaptop)) {
            assertWithMessage(untouched.id).that(AgentListOrganizer.isUnread(untouched, local, now)).isFalse()
            assertWithMessage(untouched.id).that(AgentListOrganizer.indicatorFor(untouched, local, now)).isEqualTo(AgentIndicator.Read)
        }
        // Rows, children included: only what this phone touched.
        assertThat(sections.unreadIds()).containsExactly(phone.id, opened.id)
        // A folded group of untouched chats has no dot to show; the Project has no badge.
        assertThat(sections.first { it.key == "date:Yesterday" }.rows.any { it.isUnread }).isFalse()
        val projectRow = sections.first { it.key == AgentListOrganizer.PROJECTS_KEY }.rows.single()
        assertThat(projectRow.indicator).isEqualTo(AgentIndicator.Read)
        assertThat(projectRow.descendants().none { it.isUnread }).isTrue()
        // The widget, in either list that can show an untouched chat, and the recents the New Chat pane shows.
        assertThat(widgetUnread(WidgetMode.Recent, local)).containsExactly(phone.id, opened.id)
        assertThat(widgetUnread(WidgetMode.Pinned, local)).isEmpty()
        assertThat(AgentListOrganizer.recentRows(sections).filter { it.isUnread }.map { it.agent.id }).containsExactly(phone.id, opened.id)
        // "Read all" counts what it would act on: the two touched chats, not the account's hundreds.
        assertThat(agents.map { AgentListOrganizer.toRow(it, local, now) }.count { it.isUnread }).isEqualTo(2)
        // The Unread status filter lists them alone; the rest are Read, and listed as such.
        val unreadOnly = sections(local, ListPreferences(statuses = setOf(StatusFilter.Unread)))
        assertThat(unreadOnly.flatMap { it.rows }.filterNot { it.isPinned || it.agent.isProjectRoot }.map { it.agent.id }).containsExactly(phone.id, opened.id)
    }

    @Test
    fun `a chat started here reads unread with the switch on exactly as with it off`() {
        for (switchOn in listOf(false, true)) {
            // Finished after the launch's marker: unread.
            assertWithMessage("switch on: $switchOn").that(AgentListOrganizer.isUnread(phone, local(switchOn), now)).isTrue()
            // Opened since: read, until the next activity.
            val readSince = local(switchOn).let { it.copy(readMarkers = it.readMarkers + (phone.id to phone.listedAtMillis)) }
            assertWithMessage("switch on: $switchOn").that(AgentListOrganizer.isUnread(phone, readSince, now)).isFalse()
            val later = phone.copy(updatedAtMillis = phone.listedAtMillis + hour)
            assertWithMessage("switch on: $switchOn").that(AgentListOrganizer.isUnread(later, readSince, now)).isTrue()
        }
    }

    @Test
    fun `a chat started elsewhere reads unread only once it has been opened here, then as it does today`() {
        val fresh = agent("elsewhere", updatedAgo = 30 * 60_000L)
        val before = LocalAgentState(unreadOnlyTouchedHere = true)
        // Never opened here: no dot, although this phone has no read marker for it.
        assertThat(AgentListOrganizer.isUnread(fresh, before, now)).isFalse()
        assertThat(AgentListOrganizer.isUnread(fresh, before.copy(unreadOnlyTouchedHere = false), now)).isTrue()

        // Opened here: touched, and read as of the moment it was opened — as the open writes both.
        val afterOpen = before.copy(touchedHereIds = setOf(fresh.id), readMarkers = mapOf(fresh.id to fresh.listedAtMillis))
        assertThat(AgentListOrganizer.isUnread(fresh, afterOpen, now)).isFalse()

        // The laptop sends it on; the finished turn shows as unread on the phone, as it would with the switch off.
        val later = fresh.copy(updatedAtMillis = fresh.listedAtMillis + 10 * 60_000L)
        assertThat(AgentListOrganizer.isUnread(later, afterOpen, now)).isTrue()
        assertThat(AgentListOrganizer.isUnread(later, afterOpen.copy(unreadOnlyTouchedHere = false), now)).isTrue()
    }

    @Test
    fun `a touched chat that is running, archived or snoozed is not unread, whatever the switch says`() {
        val local = local(switchOn = true).copy(snoozedUntil = mapOf(opened.id to SnoozeDuration.FOREVER))
        assertThat(AgentListOrganizer.isUnread(phone.copy(runStatus = RunStatus.RUNNING, lifecycle = AgentLifecycle.ACTIVE), local, now)).isFalse()
        assertThat(AgentListOrganizer.isUnread(phone.copy(lifecycle = AgentLifecycle.ARCHIVED), local, now)).isFalse()
        assertThat(AgentListOrganizer.isUnread(opened, local, now)).isFalse()
    }
}
