package com.cursorforandroid.notifications

import com.cursorforandroid.domain.Agent
import com.cursorforandroid.domain.AgentLifecycle
import com.cursorforandroid.domain.EnvType
import com.cursorforandroid.domain.RunStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpotlightControllerTest {

    private fun agent(id: String, project: Boolean = false) = Agent(
        id = id,
        name = id,
        lifecycle = AgentLifecycle.ACTIVE,
        runStatus = RunStatus.RUNNING,
        envType = EnvType.CLOUD,
        envName = null,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = 0L,
        updatedAtMillis = 0L,
        latestRunId = "run-$id",
        repoUrl = null,
        startingRef = null,
        isProject = project,
    )

    @Test
    fun `one Spotlight at a time, so a second replaces the first`() {
        val controller = SpotlightController { 42L }
        controller.spotlight(agent("a"))
        val second = controller.spotlight(agent("p", project = true))
        assertThat(controller.target.value).isEqualTo(second)
        assertThat(second.isProject).isTrue()
        assertThat(second.startedAtMillis).isEqualTo(42L)
        assertThat(controller.isSpotlit("a")).isFalse()
        assertThat(controller.isSpotlit("p")).isTrue()
        assertThat(controller.covered.value).containsExactly("p")
    }

    @Test
    fun `ending an old Spotlight leaves a newer one alone`() {
        val controller = SpotlightController { 0L }
        val first = controller.spotlight(agent("a"))
        controller.spotlight(agent("b"))
        controller.end(first)
        controller.cover(first, setOf("a", "x"))
        assertThat(controller.isSpotlit("b")).isTrue()
        assertThat(controller.covered.value).containsExactly("b")
    }

    @Test
    fun `stop clears the target and what it covered`() {
        val controller = SpotlightController { 0L }
        val target = controller.spotlight(agent("p", project = true))
        controller.cover(target, setOf("p", "w1", "w2"))
        assertThat(controller.covered.value).containsExactly("p", "w1", "w2")
        controller.stop()
        assertThat(controller.target.value).isNull()
        assertThat(controller.covered.value).isEmpty()
    }
}
