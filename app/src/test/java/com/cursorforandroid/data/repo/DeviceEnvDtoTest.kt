package com.cursorforandroid.data.repo

import com.cursorforandroid.data.api.CursorJson
import com.cursorforandroid.data.api.dto.AgentEnvDto
import com.cursorforandroid.domain.DeviceTarget
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceEnvDtoTest {

    @Test
    fun `default cloud with a repo omits env so the repository is the target`() {
        assertThat(DeviceTarget.Cloud.toEnvDto(hasRepo = true)).isNull()
    }

    @Test
    fun `no-repo cloud still names the empty VM`() {
        assertThat(DeviceTarget.Cloud.toEnvDto(hasRepo = false)).isEqualTo(AgentEnvDto(type = "cloud"))
    }

    @Test
    fun `a machine keeps only the worker name, even when the display string carries a workspace`() {
        assertThat(DeviceTarget.machine("bennett#/home/bennett/app").toEnvDto(hasRepo = true))
            .isEqualTo(AgentEnvDto(type = "machine", name = "bennett"))
    }

    @Test
    fun `a team pool always goes out`() {
        assertThat(DeviceTarget.pool("gpu").toEnvDto(hasRepo = true)).isEqualTo(AgentEnvDto(type = "pool", name = "gpu"))
        assertThat(DeviceTarget.pool("gpu").toEnvDto(hasRepo = false)).isEqualTo(AgentEnvDto(type = "pool", name = "gpu"))
    }

    @Test
    fun `env type is written even though it is the DTO's default`() {
        // `env.type` is required whenever `env` is sent; CursorJson leaves defaults out, so without this the cloud
        // type would vanish on the wire and the server would answer 400.
        assertThat(CursorJson.encodeToString(AgentEnvDto.serializer(), AgentEnvDto(type = "cloud", name = "staging")))
            .isEqualTo("""{"type":"cloud","name":"staging"}""")
        assertThat(CursorJson.encodeToString(AgentEnvDto.serializer(), AgentEnvDto(type = "cloud")))
            .isEqualTo("""{"type":"cloud"}""")
        assertThat(CursorJson.encodeToString(AgentEnvDto.serializer(), AgentEnvDto(type = "pool", name = "gpu")))
            .isEqualTo("""{"type":"pool","name":"gpu"}""")
    }

    @Test
    fun `a record without an env type still reads as cloud`() {
        assertThat(CursorJson.decodeFromString(AgentEnvDto.serializer(), """{}""")).isEqualTo(AgentEnvDto(type = "cloud"))
        assertThat(CursorJson.decodeFromString(AgentEnvDto.serializer(), """{"type":"machine","name":"bennett"}"""))
            .isEqualTo(AgentEnvDto(type = "machine", name = "bennett"))
    }
}
