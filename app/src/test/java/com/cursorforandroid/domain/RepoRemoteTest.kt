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

    /** The worker's `repo=` label for its checkout, and the desktop's for a request (`af` / `T1t`): the host dropped on the well-known ones and Origin. */
    @Test
    fun `the repo label is owner and name on the well-known hosts and Origin, host and path elsewhere`() {
        assertThat(RepoRemote.label("https://github.com/bennett/codex-poly-bot")).isEqualTo("bennett/codex-poly-bot")
        assertThat(RepoRemote.label("git@github.com:Acme/App.git")).isEqualTo("Acme/App")
        assertThat(RepoRemote.label("https://gitlab.com/group/sub/app")).isEqualTo("group/sub/app")
        assertThat(RepoRemote.label("https://git.acme.gitlab.com/team/app")).isEqualTo("team/app")
        assertThat(RepoRemote.label("https://origin.cursor.com/bennett/codex-poly-bot.git")).isEqualTo("bennett/codex-poly-bot")
        assertThat(RepoRemote.label("https://origin.cursor.com/git/bennett/codex-poly-bot")).isEqualTo("bennett/codex-poly-bot")
        assertThat(RepoRemote.label("https://git.internal:8080/team/app")).isEqualTo("git.internal:8080/team/app")
        assertThat(RepoRemote.label("codex-poly-bot")).isNull()
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
