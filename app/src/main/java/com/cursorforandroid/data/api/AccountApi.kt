package com.cursorforandroid.data.api

import com.cursorforandroid.data.auth.SessionTokenProvider
import kotlinx.serialization.Serializable

/** What the account service knows about the signed-in user that the public API's `/v1/me` leaves out. */
data class AccountProfile(
    val profilePictureUrl: String?,
    val email: String?,
    val firstName: String?,
    val lastName: String?,
    val teamName: String?,
)

/** The account's own profile. An interface so the session can be tested against a fake. */
interface ProfileApi {
    suspend fun profile(): AccountProfile
}

/**
 * `aiserver.v1.DashboardService/GetMe`, the call behind the account card on cursor.com/settings: the same service the
 * sign-in mints its key from, over the same Connect-JSON channel, with the session from [SessionTokenProvider]. Its
 * answer carries the profile picture the public API has no field for.
 */
class AccountApi(
    private val rpc: ConnectJsonClient,
    private val tokens: SessionTokenProvider,
) : ProfileApi {

    override suspend fun profile(): AccountProfile {
        val me = rpc.unaryWithSession(SERVICE, "GetMe", tokens, GetMeRequestDto(), GetMeRequestDto.serializer(), GetMeResponseDto.serializer())
        return AccountProfile(
            profilePictureUrl = me.profilePictureUrl?.takeIf { it.isNotBlank() },
            email = me.email?.takeIf { it.isNotBlank() },
            firstName = me.firstName?.takeIf { it.isNotBlank() },
            lastName = me.lastName?.takeIf { it.isNotBlank() },
            teamName = me.teamName?.takeIf { it.isNotBlank() },
        )
    }

    /** Every field is optional; an empty message asks about the key's own user in its default team. */
    @Serializable
    private class GetMeRequestDto

    @Serializable
    private data class GetMeResponseDto(
        val userId: Long? = null,
        val email: String? = null,
        val firstName: String? = null,
        val lastName: String? = null,
        val teamName: String? = null,
        val profilePictureUrl: String? = null,
    )

    companion object {
        const val SERVICE = "aiserver.v1.DashboardService"
    }
}
