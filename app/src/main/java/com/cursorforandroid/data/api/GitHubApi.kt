package com.cursorforandroid.data.api

import com.cursorforandroid.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/** The fields of GitHub's pull request record that say where it stands; `merged` is only ever set on a closed one. */
@Serializable
data class GitHubPullRequestDto(
    val state: String = "",
    val draft: Boolean = false,
    val merged: Boolean = false,
    @SerialName("merged_at") val mergedAt: String? = null,
)

/**
 * The one corner of GitHub's REST API the app reads: a pull request's state, which the Cloud Agents API does not
 * report. Answers are returned as [Response] so a `404` (a private repository without a token, a PR that is gone) and
 * a spent rate limit (`403` / `429` with `X-RateLimit-Remaining: 0`) can be told apart from a real failure.
 */
interface GitHubApi {
    @GET("repos/{owner}/{repo}/pulls/{number}")
    suspend fun pullRequest(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
    ): Response<GitHubPullRequestDto>
}

object GitHubEndpoints {
    const val BASE_URL = "https://api.github.com/"
    /** Where a fine-grained token with read access to pull requests is created. */
    const val NEW_TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"
}

object GitHubApiFactory {

    /**
     * A client of its own: the Cursor key must never travel to GitHub, and GitHub's rate limits are not to be retried
     * through — a `429` here is a budget spent, not a blip. The token is read per request so a change in Settings
     * applies to the next call.
     */
    fun okHttp(tokenProvider: () -> String?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = tokenProvider()?.takeIf { it.isNotBlank() }
            val request = chain.request().newBuilder()
                .header("User-Agent", "cursor-for-android/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .build()
            chain.proceed(request)
        }
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            }
        }
        .build()

    fun retrofit(client: OkHttpClient, baseUrl: String = GitHubEndpoints.BASE_URL): GitHubApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(CursorJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(GitHubApi::class.java)
}
