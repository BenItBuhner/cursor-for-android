package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.SlashCatalog
import com.cursorforandroid.domain.SlashCommand
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The `/` commands a cloud composer can offer beyond the built-ins, from whoever can say: the account service (in
 * Extended mode) or the repository's own contents. An interface so the repository can be tested against a fake.
 */
interface SlashCommandApi {
    /** What a new chat on [repoUrl] at [ref] (blank for the default branch) can lead with: the built-in, project, plugin and synced skills. */
    suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog

    /**
     * What a follow-up to [agentId] can lead with: the skills, plus the `.cursor/commands` its machine reported.
     * [repoUrl] and [ref] help the server while the machine's inventory is still pending.
     */
    suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog

    /** The commands that apply everywhere — `/goal` and the team's — as far as they are meant for cloud agents. */
    suspend fun global(): List<SlashCommand>
}

/** A source with nothing to add: the composers keep the built-ins. */
object NoSlashCommandApi : SlashCommandApi {
    override suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog = SlashCatalog()
    override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog = SlashCatalog()
    override suspend fun global(): List<SlashCommand> = emptyList()
}

/**
 * `aiserver.v1.DashboardService`'s slash-command corner, the calls behind the `/` popover on cursor.com/agents and in
 * the Agents window (as `@cursor/sdk` bundles them): `GetRepoSlashCommands` for the New Chat composer,
 * `GetBackgroundComposerSlashCommands` for a chat's follow-ups (a cloud agent is a "background composer" there, and
 * its `bcId` is the agent id the public API uses), and `GetGlobalCommands` for the commands that are not tied to a
 * repository. Over the same Connect-JSON channel as `GetMe`, with the session from [SessionTokenProvider].
 */
class DashboardSlashCommandApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : SlashCommandApi {

    override suspend fun forRepository(repoUrl: String, ref: String?): SlashCatalog {
        val response = call(
            "GetRepoSlashCommands",
            RepoRequestDto(repoUrl = repoUrl, gitRef = ref?.takeIf { it.isNotBlank() }, includeProjectPluginSkills = true),
            RepoRequestDto.serializer(),
            SlashCommandsResponseDto.serializer(),
        )
        return response.toCatalog()
    }

    override suspend fun forAgent(agentId: String, repoUrl: String?, ref: String?): SlashCatalog {
        val response = call(
            "GetBackgroundComposerSlashCommands",
            AgentRequestDto(bcId = agentId, repoUrl = repoUrl?.takeIf { it.isNotBlank() }, gitRef = ref?.takeIf { it.isNotBlank() }),
            AgentRequestDto.serializer(),
            SlashCommandsResponseDto.serializer(),
        )
        return response.toCatalog()
    }

    override suspend fun global(): List<SlashCommand> {
        val response = call("GetGlobalCommands", GlobalRequestDto(surface = SURFACE), GlobalRequestDto.serializer(), GlobalResponseDto.serializer())
        return response.commands.filter { it.appliesToCloud() }.mapNotNull { it.toCommand() }
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    // Requests: proto3 JSON, lowerCamelCase; CursorJson leaves nulls out, so an unset optional field is simply absent.

    @Serializable
    private data class RepoRequestDto(val repoUrl: String, val gitRef: String? = null, val includeProjectPluginSkills: Boolean)

    @Serializable
    private data class AgentRequestDto(val bcId: String, val repoUrl: String? = null, val gitRef: String? = null)

    @Serializable
    private data class GlobalRequestDto(val surface: String)

    /**
     * `GetRepoSlashCommandsResponse` and `GetBackgroundComposerSlashCommandsResponse` share their shape; the latter
     * also carries the machine's `commands` and whether its inventory is still on its way. `skillNames` /
     * `commandNames` are the older name-only fields, kept for entries the descriptor lists leave out.
     */
    @Serializable
    private data class SlashCommandsResponseDto(
        val skillNames: List<String> = emptyList(),
        val commandNames: List<String> = emptyList(),
        val skills: List<SkillDescriptorDto> = emptyList(),
        val commands: List<CommandDescriptorDto> = emptyList(),
        val machineInventoryPending: Boolean? = null,
    ) {
        fun toCatalog(): SlashCatalog {
            val entries = LinkedHashMap<String, SlashCommand>()
            fun put(command: SlashCommand) {
                if (command.name.isNotBlank() && command.name !in entries) entries[command.name] = command
            }
            commands.forEach { put(it.toCommand()) }
            commandNames.forEach { put(SlashCommand(it.trim().removePrefix("/"), kind = SlashCommand.Kind.Command, origin = SlashCommand.Origin.Project)) }
            skills.filterNot { it.disabledForCloud() }.forEach { put(it.toSkill()) }
            skillNames.forEach { put(SlashCommand(it.trim().removePrefix("/"), kind = SlashCommand.Kind.Skill)) }
            return SlashCatalog(entries.values.toList(), pending = machineInventoryPending == true)
        }
    }

    /** `aiserver.v1.SkillDescriptor`. `customMode`, `icon` and `color` are the desktop's business and ignored. */
    @Serializable
    private data class SkillDescriptorDto(
        val name: String = "",
        val description: String? = null,
        val sourcePath: String? = null,
        val sourceUrl: String? = null,
        val environments: List<String> = emptyList(),
        val disabledEnvironments: List<String> = emptyList(),
        val displayName: String? = null,
    ) {
        /**
         * A skill's front matter can switch it off per environment; the cloud VM is one of them. The vocabulary is
         * the skill author's, so only an unambiguous "cloud" is honoured.
         */
        fun disabledForCloud(): Boolean = disabledEnvironments.any { it.equals("cloud", ignoreCase = true) }

        fun toSkill(): SlashCommand = SlashCommand(
            name = name.trim().removePrefix("/"),
            description = description?.trim().orEmpty(),
            kind = SlashCommand.Kind.Skill,
            origin = SlashCommand.originOf(sourcePath, sourceUrl),
            sourcePath = sourcePath?.takeIf { it.isNotBlank() },
        )
    }

    /** `aiserver.v1.CommandDescriptor`: a `.cursor/commands/<name>.md` on the agent's machine. */
    @Serializable
    private data class CommandDescriptorDto(
        val name: String = "",
        val description: String? = null,
        val sourcePath: String? = null,
        val sourceUrl: String? = null,
    ) {
        fun toCommand(): SlashCommand = SlashCommand(
            name = name.trim().removePrefix("/"),
            description = description?.trim().orEmpty(),
            kind = SlashCommand.Kind.Command,
            origin = SlashCommand.originOf(sourcePath, sourceUrl).takeIf { it != SlashCommand.Origin.Unknown } ?: SlashCommand.Origin.Project,
            sourcePath = sourcePath?.takeIf { it.isNotBlank() },
        )
    }

    @Serializable
    private data class GlobalResponseDto(val commands: List<GlobalCommandDto> = emptyList())

    /**
     * `aiserver.v1.GlobalCommand`. [availability] and [disabledSurfaces] are enums: their names in proto3's JSON
     * mapping, or their numbers when a server encodes enums that way. `content` is the prompt the command expands to
     * on the server's side and is not needed here.
     */
    @Serializable
    private data class GlobalCommandDto(
        val name: String = "",
        val description: String? = null,
        val availability: JsonPrimitive? = null,
        val argumentHint: String? = null,
        val disabledSurfaces: List<JsonPrimitive> = emptyList(),
    ) {
        fun appliesToCloud(): Boolean {
            val local = when (availability?.contentOrNull?.uppercase()) {
                "GLOBAL_COMMAND_AVAILABILITY_LOCAL_ONLY", "3" -> true
                else -> false
            }
            val disabledHere = disabledSurfaces.any { val v = it.contentOrNull?.uppercase(); v == SURFACE || v == SURFACE_NUMBER }
            return !local && !disabledHere && name.isNotBlank()
        }

        /** The server does not say whether a global command is Cursor's own or the team's, so the origin is left open and a built-in's stands. */
        fun toCommand(): SlashCommand? = name.trim().removePrefix("/").takeIf { it.isNotBlank() }?.let { n ->
            SlashCommand(
                name = n,
                description = description?.trim().orEmpty(),
                kind = SlashCommand.Kind.Command,
                origin = SlashCommand.Origin.Unknown,
                argumentHint = argumentHint?.takeIf { it.isNotBlank() },
            )
        }
    }

    companion object {
        const val SERVICE = "aiserver.v1.DashboardService"

        /**
         * `aiserver.v1.GlobalCommandSurface`: the composer here is the Agents window's ("glass" is what the desktop
         * calls that UI, and the README's design tokens come from the same `workbench.glass.*` build), so commands a
         * team switched off for it stay off here too.
         */
        const val SURFACE = "GLOBAL_COMMAND_SURFACE_GLASS"
        private const val SURFACE_NUMBER = "2"
    }
}
