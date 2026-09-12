package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import com.cursorforandroid.domain.MachineReference
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Where the agent's machine — and so its desktop — is. An interface so the repositories can be faked. */
fun interface MachineLookupApi {
    /** `GetMachine {bcId, mintDesktopTicket}`: the pod or worker behind the chat, with a token good for its desktop. */
    suspend fun machine(agentId: String): MachineReference
}

/**
 * `GetMachine` on `aiserver.v1.BackgroundComposerService` (see [BackgroundComposerApi]), asked with `mintDesktopTicket`
 * so the answer carries a short-lived token for the desktop rather than the pod's standing one. Cursor's descriptors
 * name the flag but not what it changes in the answer (spec §9, item 5), so the reply is read loosely: a pod's
 * `networkToken` is the token unless a field that calls itself a ticket sits beside it, and a server that rejects the
 * flag as unknown is asked once more without it. Every call carries the account session from [SessionTokenProvider],
 * which Extended mode alone hands out.
 */
class MachineApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : MachineLookupApi {

    override suspend fun machine(agentId: String): MachineReference {
        val response = try {
            call("GetMachine", GetMachineDto(agentId, mintDesktopTicket = true), GetMachineDto.serializer(), GetMachineResponseDto.serializer())
        } catch (e: ConnectRpcException) {
            if (!e.rejectsArgument) throw e
            call("GetMachine", BcIdDto(agentId), BcIdDto.serializer(), GetMachineResponseDto.serializer())
        }
        return referenceOf(response) ?: throw ConnectRpcException(200, null, "Cursor named no machine for this chat.")
    }

    private suspend fun <I, O> call(method: String, body: I, requestSerializer: KSerializer<I>, responseSerializer: KSerializer<O>): O =
        rpc.unaryWithSession(BackgroundComposerApi.SERVICE, method, tokens, body, requestSerializer, responseSerializer)

    /** `invalid_argument` is how a Connect server says a field was not understood (or not allowed). */
    private val ConnectRpcException.rejectsArgument: Boolean get() = code == "invalid_argument" || httpCode == 400

    /** [mintDesktopTicket] has no default so `true` is encoded (`CursorJson` drops defaulted fields). */
    @Serializable
    private data class GetMachineDto(val bcId: String, val mintDesktopTicket: Boolean)

    @Serializable
    private data class BcIdDto(val bcId: String)

    @Serializable
    internal data class GetMachineResponseDto(val machine: MachineReferenceDto? = null)

    /** `aiserver.v1.MachineReference`: oneof `pod` | `worker`. */
    @Serializable
    internal data class MachineReferenceDto(val pod: JsonObject? = null, val worker: WorkerReferenceDto? = null)

    /** `aiserver.v1.WorkerReference`, with the 3.20 `desktop: WorkerDesktopCapability` read when present. */
    @Serializable
    internal data class WorkerReferenceDto(
        val workerId: String? = null,
        val workspaceRootPath: String? = null,
        val desktop: WorkerDesktopDto? = null,
    )

    @Serializable
    internal data class WorkerDesktopDto(
        val available: Boolean? = null,
        val wsUrl: String? = null,
        val controlAllowed: Boolean? = null,
        val unavailableReason: String? = null,
    )

    internal companion object {
        /**
         * The pod's fields, read off the object rather than a fixed shape: `podId`, `tenantId`, `cluster` and
         * `networkToken` as the descriptors name them, and any string field whose name contains "ticket" as the
         * minted desktop ticket, which then takes the token's place in the URL.
         */
        fun referenceOf(response: GetMachineResponseDto): MachineReference? {
            val machine = response.machine ?: return null
            machine.pod?.let { pod ->
                val podId = pod.string("podId", "pod_id") ?: return@let
                val tenantId = pod.string("tenantId", "tenant_id") ?: return@let
                val cluster = pod.string("cluster") ?: return@let
                val ticket = pod.entries.firstOrNull { (key, value) -> key.contains("ticket", ignoreCase = true) && value.stringOrNull() != null }?.value?.stringOrNull()
                val token = ticket ?: pod.string("networkToken", "network_token") ?: return@let
                return MachineReference.Pod(podId = podId, tenantId = tenantId, cluster = cluster, token = token, ticketMinted = ticket != null)
            }
            machine.worker?.let { worker ->
                return MachineReference.Worker(
                    workerId = worker.workerId?.takeIf { it.isNotBlank() } ?: "worker",
                    workspaceRootPath = worker.workspaceRootPath?.takeIf { it.isNotBlank() },
                    available = worker.desktop?.available == true && !worker.desktop.wsUrl.isNullOrBlank(),
                    controlAllowed = worker.desktop?.controlAllowed == true,
                    unavailableReason = worker.desktop?.unavailableReason?.takeIf { it.isNotBlank() },
                )
            }
            return null
        }

        private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { this[it]?.stringOrNull() }

        private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull == null }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
