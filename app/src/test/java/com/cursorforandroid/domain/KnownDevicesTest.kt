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
        repoUrl: String? = "https://github.com/acme/app",
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
        repoUrl = repoUrl,
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

    /** A machine's checkout is what its chats ran in: the newest chat's repository is the row's, an older one's fills a gap. */
    @Test
    fun `a harvested device carries the repository of the newest chat that ran on it`() {
        val listed = KnownDevices.compose(
            listOf(
                agent("older", EnvType.MACHINE, "box#/home/dev/app", updatedAgo = 2 * hour, repoUrl = "https://github.com/acme/app"),
                agent("newer", EnvType.MACHINE, "box#/home/dev/infra", updatedAgo = hour, repoUrl = "https://github.com/acme/infra"),
                agent("no-repo", EnvType.POOL, "gpu", updatedAgo = 0, repoUrl = null),
                agent("with-repo", EnvType.POOL, "gpu", updatedAgo = hour, repoUrl = "https://github.com/acme/ml"),
            ),
        )
        assertThat(KnownDevices.repositoryOf(listed, DeviceTarget.machine("box"))).isEqualTo("https://github.com/acme/infra")
        // The pool's newest chat had no repository (an any-repo request); the one before it still says where the pool works.
        assertThat(KnownDevices.repositoryOf(listed, DeviceTarget.pool("gpu"))).isEqualTo("https://github.com/acme/ml")
        assertThat(KnownDevices.repositoryOf(listed, DeviceTarget.Cloud)).isNull()
        assertThat(KnownDevices.repositoryOf(listed, DeviceTarget.machine("unheard-of"))).isNull()
    }

    /** The fleet endpoint's word on the repository stands over the chats': an any-repo worker pins none, whatever ran on it. */
    @Test
    fun `a live row's repository is the fleet's word, chats only date it`() {
        val pinned = DeviceOption(target = DeviceTarget.machine("box"), subtitle = "/home/dev/app", online = true, section = DeviceSection.Machines, repoUrl = "https://github.com/acme/app")
        val anyRepo = DeviceOption(target = DeviceTarget.machine("sandbox"), subtitle = "Online", online = true, section = DeviceSection.Machines, repoUrl = null)
        val listed = KnownDevices.compose(
            agents = listOf(
                agent("box-chat", EnvType.MACHINE, "box", updatedAgo = 0, repoUrl = "https://github.com/acme/infra"),
                agent("sandbox-chat", EnvType.MACHINE, "sandbox", updatedAgo = 0, repoUrl = "https://github.com/acme/infra"),
            ),
            live = listOf(pinned, anyRepo),
        )
        assertThat(KnownDevices.repositoryOf(listed, DeviceTarget.machine("box"))).isEqualTo("https://github.com/acme/app")
        assertThat(KnownDevices.repositoryOf(listed, DeviceTarget.machine("sandbox"))).isNull()
        assertThat(listed.first { it.target == DeviceTarget.machine("box") }.lastUsedAtMillis).isEqualTo(now)
    }

    @Test
    fun `the fleet's repository fields become a GitHub URL, or nothing for an any-repo worker`() {
        assertThat(DeviceOption.repositoryUrl("https://github.com/acme/app", "acme", "app")).isEqualTo("https://github.com/acme/app")
        assertThat(DeviceOption.repositoryUrl(null, "acme", "app")).isEqualTo("https://github.com/acme/app")
        assertThat(DeviceOption.repositoryUrl(" ", " acme ", "app")).isEqualTo("https://github.com/acme/app")
        // "Empty strings for any-repo workers" (`repoOwner`, `repoName`); "Omitted for any-repo workers" (`repoUrl`).
        assertThat(DeviceOption.repositoryUrl(null, "", "")).isNull()
        assertThat(DeviceOption.repositoryUrl(null, null, null)).isNull()
        assertThat(DeviceOption.repositoryUrl(null, "acme", "")).isNull()
    }
}
