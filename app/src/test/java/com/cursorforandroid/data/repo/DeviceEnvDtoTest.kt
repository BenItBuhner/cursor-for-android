package com.cursorforandroid.data.repo

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
}
