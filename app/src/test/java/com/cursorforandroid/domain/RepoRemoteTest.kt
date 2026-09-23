package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Remotes as a worker's checkout may spell them, in the `https://host/owner/name` form the desktop sends (`J5`). */
class RepoRemoteTest {

    @Test
    fun `web urls keep their host and path, without the git suffix, credentials or trailing slash`() {
        assertThat(RepoRemote.canonical("https://github.com/acme/app")).isEqualTo("https://github.com/acme/app")
        assertThat(RepoRemote.canonical("  https://GitHub.com/Acme/App.GIT/ ")).isEqualTo("https://github.com/Acme/App")
        assertThat(RepoRemote.canonical("https://user:secret@github.com/acme/app.git")).isEqualTo("https://github.com/acme/app")
        assertThat(RepoRemote.canonical("http://git.internal:8080/team/app")).isEqualTo("http://git.internal:8080/team/app")
        assertThat(RepoRemote.canonical("https://gitlab.com/group/sub/app.git")).isEqualTo("https://gitlab.com/group/sub/app")
        assertThat(RepoRemote.canonical("https://origin.cursor.com/bennett/codex-poly-bot.git")).isEqualTo("https://origin.cursor.com/bennett/codex-poly-bot")
    }

    @Test
    fun `ssh, git and scp-style remotes become https on the same host`() {
        assertThat(RepoRemote.canonical("git@github.com:acme/app.git")).isEqualTo("https://github.com/acme/app")
        assertThat(RepoRemote.canonical("github.com:acme/app")).isEqualTo("https://github.com/acme/app")
        assertThat(RepoRemote.canonical("ssh://git@github.com:22/acme/app.git")).isEqualTo("https://github.com/acme/app")
        assertThat(RepoRemote.canonical("git://github.com/acme/app")).isEqualTo("https://github.com/acme/app")
        assertThat(RepoRemote.canonical("github.com/acme/app")).isEqualTo("https://github.com/acme/app")
        assertThat(RepoRemote.canonical("git.internal:8080/team/app")).isEqualTo("https://git.internal:8080/team/app")
    }

    @Test
    fun `a remote without an owner and a repository is not a url to send`() {
        assertThat(RepoRemote.canonical(null)).isNull()
        assertThat(RepoRemote.canonical("  ")).isNull()
        assertThat(RepoRemote.canonical("https://github.com/acme")).isNull()
        assertThat(RepoRemote.canonical("https:///acme/app")).isNull()
        assertThat(RepoRemote.canonical("codex-poly-bot")).isNull()
    }
}
