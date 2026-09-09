package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KnownDevicesTest {

    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    private fun agent(
        id: String,
        env: EnvType = EnvType.CLOUD,
        envName: String? = null,
        updatedAgo: Long = 0,
        name: String = id,
    ) = Agent(
        id = id,
        name = name,
        lifecycle = AgentLifecycle.IDLE,
        runStatus = RunStatus.FINISHED,
        envType = env,
        envName = envName,
        url = "https://cursor.com/agents/$id",
        createdAtMillis = now - updatedAgo - hour,
        updatedAtMillis = now - updatedAgo,
        latestRunId = "run-$id",
        repoUrl = "https://github.com/acme/app",
        startingRef = "main",
    )

    @Test
    fun `cloud is always first and this device is never a row`() {
        val listed = KnownDevices.compose(emptyList())
        assertThat(listed.map { it.target }).containsExactly(DeviceTarget.Cloud)
        assertThat(listed.single().online).isTrue()
        assertThat(listed.none { it.target.label.equals("This device", ignoreCase = true) }).isTrue()
        assertThat(listed.none { it.target.label.equals("Local", ignoreCase = true) }).isTrue()
    }

    @Test
    fun `machines and pools from the agent list are harvested, newest first, workspace path stripped`() {
        val listed = KnownDevices.compose(
            listOf(
                agent("old-box", EnvType.MACHINE, "box#/old", updatedAgo = 3 * hour, name = "Older box chat"),
                agent("new-box", EnvType.MACHINE, "box#/home/dev", updatedAgo = 0, name = "Newest box chat"),
                agent("gpu", EnvType.POOL, "gpu", updatedAgo = hour, name = "GPU job"),
                agent("cloud", EnvType.CLOUD, null),
            ),
        )
        assertThat(listed.map { it.target }).containsExactly(
            DeviceTarget.Cloud,
            DeviceTarget.machine("box"),
            DeviceTarget.pool("gpu"),
        ).inOrder()
        assertThat(listed[1].target.apiName).isEqualTo("box")
        assertThat(listed[1].target.label).isEqualTo("box")
        assertThat(listed[1].subtitle).isEqualTo("Last used by Newest box chat")
        assertThat(listed[1].online).isFalse()
        assertThat(listed[2].subtitle).isEqualTo("Last used by GPU job")
    }

    @Test
    fun `a live worker wins over a harvested one of the same name and keeps its last-used time`() {
        val live = DeviceOption(
            target = DeviceTarget.machine("box"),
            subtitle = "Online",
            online = true,
            lastUsedAtMillis = 0,
            section = DeviceSection.Machines,
        )
        val listed = KnownDevices.compose(
            agents = listOf(agent("used", EnvType.MACHINE, "box", updatedAgo = 0, name = "Used box")),
            live = listOf(live),
        )
        val box = listed.first { it.target == DeviceTarget.machine("box") }
        assertThat(box.online).isTrue()
        assertThat(box.subtitle).isEqualTo("Online")
        assertThat(box.lastUsedAtMillis).isEqualTo(now)
    }

    @Test
    fun `a selected device that nothing else has heard of still has a row`() {
        val listed = KnownDevices.compose(
            agents = emptyList(),
            live = emptyList(),
            selected = DeviceTarget.machine("studio"),
        )
        assertThat(listed.map { it.target }).containsExactly(DeviceTarget.Cloud, DeviceTarget.machine("studio")).inOrder()
        assertThat(listed[1].online).isFalse()
    }

    @Test
    fun `unnamed cloud agents are not a second cloud row`() {
        val listed = KnownDevices.compose(listOf(agent("a"), agent("b", EnvType.CLOUD, "")))
        assertThat(listed).hasSize(1)
        assertThat(listed.single().target).isEqualTo(DeviceTarget.Cloud)
    }
}
