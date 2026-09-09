package com.cursorforandroid.domain

import kotlinx.serialization.Serializable

/**
 * Where a new chat should run: Cursor-hosted cloud (the default), a self-hosted team pool, or one of the user's
 * connected machines. There is no "this device" / local option — an Android client cannot host the agent loop.
 *
 * [name] is what `POST /v1/agents` sends as `env.name`: a pool name, a My Machines worker name, or a named cloud
 * environment. Blank with [type] `CLOUD` is the ordinary Cursor-hosted VM. A machine's API name is sometimes
 * `worker#/workspace/path`; [apiName] keeps the worker, [label] shows it.
 */
@Serializable
data class DeviceTarget(
    val type: EnvType = EnvType.CLOUD,
    val name: String? = null,
) {
    /** Ordinary Cursor-hosted cloud, no named environment. */
    val isCloud: Boolean get() = type == EnvType.CLOUD && apiName == null

    /** `env.name` as the create request wants it: the worker or pool, never the workspace path after `#`. */
    val apiName: String? get() = name?.substringBefore('#')?.trim()?.takeIf { it.isNotEmpty() }

    /** Composer chip text: "Cloud", the pool, or the machine. */
    val label: String
        get() = when {
            isCloud -> "Cloud"
            type == EnvType.POOL -> apiName ?: "Team pool"
            type == EnvType.MACHINE -> apiName ?: "My machine"
            else -> apiName ?: "Cloud"
        }

    companion object {
        val Cloud: DeviceTarget = DeviceTarget(EnvType.CLOUD, null)
        fun pool(name: String): DeviceTarget = DeviceTarget(EnvType.POOL, normalize(name))
        fun machine(name: String): DeviceTarget = DeviceTarget(EnvType.MACHINE, normalize(name))
        fun of(type: EnvType, name: String?): DeviceTarget {
            val normalized = name?.let(::normalize)
            return when {
                type == EnvType.CLOUD || type == EnvType.UNKNOWN -> DeviceTarget(type, normalized)
                normalized == null -> Cloud
                else -> DeviceTarget(type, normalized)
            }
        }

        /** Worker names sometimes arrive as `name#/workspace`; the create request only wants the name. */
        private fun normalize(name: String): String = name.substringBefore('#').trim().ifEmpty { name.trim() }
    }
}

/** How the device picker groups a row. Cloud is always first; this phone is never a row. */
enum class DeviceSection { Cloud, Machines, Pools }

/**
 * One row of the device picker: a [target] the create request can send, plus the words that explain it
 * (online / busy / last used). [online] is true for Cursor cloud and for workers the fleet endpoints currently list.
 */
data class DeviceOption(
    val target: DeviceTarget,
    val subtitle: String? = null,
    val online: Boolean = false,
    val lastUsedAtMillis: Long = 0L,
    val section: DeviceSection,
) {
    val key: String get() = keyOf(target)

    companion object {
        fun keyOf(target: DeviceTarget): String = "${target.type.name}:${target.apiName.orEmpty()}"
    }
}

/**
 * The devices the composer can offer. The Cloud Agents API has no dedicated "list my machines" on every key —
 * user keys often cannot call the fleet endpoints, which want a pool service account — so the picker is built
 * the way branches are: Cloud is always there, live workers and pools from `/v0/private-workers` when the key
 * can see them, and every pool or machine the agent list has already run on.
 */
object KnownDevices {

    val cloud: DeviceOption = DeviceOption(
        target = DeviceTarget.Cloud,
        subtitle = "Cursor-hosted VM",
        online = true,
        section = DeviceSection.Cloud,
    )

    /**
     * Cloud, then machines, then team pools. Live rows win on a name clash (they know whether the worker is up);
     * harvested ones fill in anything the fleet endpoints did not return. [selected] is kept in the list even when
     * nothing else has heard of it, so a restored last-launch choice still has a checked row.
     */
    fun compose(
        agents: List<Agent>,
        live: List<DeviceOption> = emptyList(),
        selected: DeviceTarget = DeviceTarget.Cloud,
    ): List<DeviceOption> {
        val liveByKey = linkedMapOf<String, DeviceOption>()
        for (option in live) {
            if (option.section == DeviceSection.Cloud) continue
            liveByKey.putIfAbsent(option.key, option)
        }
        val harvested = LinkedHashMap<String, DeviceOption>()
        for (agent in agents) {
            val target = targetOf(agent) ?: continue
            val key = DeviceOption.keyOf(target)
            val liveHit = liveByKey[key]
            if (liveHit != null) {
                if (agent.updatedAtMillis > liveHit.lastUsedAtMillis) {
                    liveByKey[key] = liveHit.copy(lastUsedAtMillis = agent.updatedAtMillis)
                }
                continue
            }
            val existing = harvested[key]
            if (existing == null || agent.updatedAtMillis > existing.lastUsedAtMillis) {
                harvested[key] = DeviceOption(
                    target = target,
                    subtitle = "Last used by ${agent.name}",
                    online = false,
                    lastUsedAtMillis = agent.updatedAtMillis,
                    section = sectionOf(target),
                )
            }
        }
        val selectedOption = selected.takeUnless { it.isCloud }?.let { target ->
            val key = DeviceOption.keyOf(target)
            if (key in liveByKey || key in harvested) null
            else DeviceOption(target = target, subtitle = null, online = false, section = sectionOf(target))
        }
        fun listed(section: DeviceSection): List<DeviceOption> {
            val rows = liveByKey.values.filter { it.section == section } +
                harvested.values.filter { it.section == section } +
                listOfNotNull(selectedOption?.takeIf { it.section == section })
            return rows.sortedWith(
                compareByDescending<DeviceOption> { it.online }
                    .thenByDescending { it.lastUsedAtMillis }
                    .thenBy { it.target.label.lowercase() },
            )
        }
        return listOf(cloud) + listed(DeviceSection.Machines) + listed(DeviceSection.Pools)
    }

    /** A pool or machine the agent ran on; unnamed cloud rows are not a pickable device of their own. */
    fun targetOf(agent: Agent): DeviceTarget? {
        val raw = agent.envName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (agent.envType) {
            EnvType.MACHINE -> DeviceTarget.machine(raw).takeIf { it.apiName != null }
            EnvType.POOL -> DeviceTarget.pool(raw).takeIf { it.apiName != null }
            else -> null
        }
    }

    private fun sectionOf(target: DeviceTarget): DeviceSection = when (target.type) {
        EnvType.POOL -> DeviceSection.Pools
        EnvType.MACHINE -> DeviceSection.Machines
        else -> DeviceSection.Cloud
    }
}
