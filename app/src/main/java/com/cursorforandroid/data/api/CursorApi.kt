package com.cursorforandroid.data.api

import com.cursorforandroid.data.api.dto.AgentDto
import com.cursorforandroid.data.api.dto.AgentUsageResponseDto
import com.cursorforandroid.data.api.dto.ApiKeyInfoDto
import com.cursorforandroid.data.api.dto.CreateAgentRequestDto
import com.cursorforandroid.data.api.dto.CreateAgentResponseDto
import com.cursorforandroid.data.api.dto.CreateRunRequestDto
import com.cursorforandroid.data.api.dto.CreateRunResponseDto
import com.cursorforandroid.data.api.dto.DownloadArtifactResponseDto
import com.cursorforandroid.data.api.dto.IdResponseDto
import com.cursorforandroid.data.api.dto.ListAgentsResponseDto
import com.cursorforandroid.data.api.dto.ListArtifactsResponseDto
import com.cursorforandroid.data.api.dto.ListModelsResponseDto
import com.cursorforandroid.data.api.dto.ListRepositoriesResponseDto
import com.cursorforandroid.data.api.dto.ListRunsResponseDto
import com.cursorforandroid.data.api.dto.RunDto
import com.cursorforandroid.data.api.dto.V0ConversationResponseDto
import com.cursorforandroid.data.api.dto.V0ListAgentsResponseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Cursor Cloud Agents API. v1 is the primary surface (durable agents + per-prompt runs). Two legacy v0 endpoints
 * are still used because v1 has no equivalent: the transcript (`/v0/agents/{id}/conversation`) and the richer
 * list item (`/v0/agents`) that carries repo / branch / PR / summary in one round-trip.
 */
interface CursorApi {

    // -- metadata --
    @GET("v1/me")
    suspend fun me(): ApiKeyInfoDto

    @GET("v1/models")
    suspend fun models(): ListModelsResponseDto

    @GET("v1/repositories")
    suspend fun repositories(): ListRepositoriesResponseDto

    // -- agents --
    @GET("v1/agents")
    suspend fun listAgents(
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null,
        @Query("includeArchived") includeArchived: Boolean = true,
    ): ListAgentsResponseDto

    @GET("v1/agents/{id}")
    suspend fun getAgent(@Path("id") id: String): AgentDto

    @POST("v1/agents")
    suspend fun createAgent(@Body body: CreateAgentRequestDto): CreateAgentResponseDto

    @POST("v1/agents/{id}/archive")
    suspend fun archive(@Path("id") id: String): IdResponseDto

    @POST("v1/agents/{id}/unarchive")
    suspend fun unarchive(@Path("id") id: String): IdResponseDto

    @DELETE("v1/agents/{id}")
    suspend fun delete(@Path("id") id: String): IdResponseDto

    @GET("v1/agents/{id}/usage")
    suspend fun usage(@Path("id") id: String): AgentUsageResponseDto

    @GET("v1/agents/{id}/artifacts")
    suspend fun artifacts(@Path("id") id: String): ListArtifactsResponseDto

    @GET("v1/agents/{id}/artifacts/download")
    suspend fun artifactUrl(@Path("id") id: String, @Query("path") path: String): DownloadArtifactResponseDto

    // -- runs --
    @GET("v1/agents/{id}/runs")
    suspend fun listRuns(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("cursor") cursor: String? = null,
    ): ListRunsResponseDto

    @GET("v1/agents/{id}/runs/{runId}")
    suspend fun getRun(@Path("id") id: String, @Path("runId") runId: String): RunDto

    @POST("v1/agents/{id}/runs")
    suspend fun createRun(@Path("id") id: String, @Body body: CreateRunRequestDto): CreateRunResponseDto

    @POST("v1/agents/{id}/runs/{runId}/cancel")
    suspend fun cancelRun(@Path("id") id: String, @Path("runId") runId: String): IdResponseDto

    // -- legacy v0 --
    @GET("v0/agents")
    suspend fun listAgentsV0(
        @Query("limit") limit: Int = 100,
        @Query("cursor") cursor: String? = null,
    ): V0ListAgentsResponseDto

    @GET("v0/agents/{id}/conversation")
    suspend fun conversationV0(@Path("id") id: String): V0ConversationResponseDto
}

object CursorEndpoints {
    const val BASE_URL = "https://api.cursor.com/"
    fun streamUrl(agentId: String, runId: String) = "${BASE_URL}v1/agents/$agentId/runs/$runId/stream"
    fun webUrl(agentId: String) = "https://cursor.com/agents/$agentId"
    const val DASHBOARD_API_KEYS = "https://cursor.com/dashboard/api"
}
