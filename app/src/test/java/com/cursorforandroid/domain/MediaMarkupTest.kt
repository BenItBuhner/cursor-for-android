package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class MediaMarkupTest {

    @Test
    fun `html img tags become image segments and surrounding text is kept`() {
        val segments = MediaMarkup.split(
            "Before/after: <img alt=\"Web recording vs Android\" src=\"/opt/cursor/artifacts/running_glyph.png\" /> and done.",
        )
        assertThat(segments).containsExactly(
            MediaSegment.Text("Before/after:"),
            MediaSegment.Image("/opt/cursor/artifacts/running_glyph.png", "Web recording vs Android"),
            MediaSegment.Text("and done."),
        ).inOrder()
    }

    @Test
    fun `video tags take src from the tag or a nested source and drop the fallback text`() {
        assertThat(MediaMarkup.split("<video src=\"/opt/cursor/artifacts/demo.mp4\"></video>"))
            .containsExactly(MediaSegment.Video("/opt/cursor/artifacts/demo.mp4", null))
        assertThat(MediaMarkup.split("<video controls poster=\"p.png\">\n<source src=\"clip.mp4\" type=\"video/mp4\">\nNo video support.\n</video>"))
            .containsExactly(MediaSegment.Video("clip.mp4", "p.png"))
        // An unterminated <video> still renders; a stray closing tag later never leaks into the text.
        assertThat(MediaMarkup.split("<video src=\"a.mp4\"> trailing </video> text"))
            .containsExactly(MediaSegment.Video("a.mp4", null), MediaSegment.Text("text")).inOrder()
    }

    @Test
    fun `markdown images and linked images are recognised`() {
        assertThat(MediaMarkup.split("![Demo](https://example.com/demo.png \"title\")"))
            .containsExactly(MediaSegment.Image("https://example.com/demo.png", "Demo"))
        assertThat(MediaMarkup.split("[![Shot](/opt/cursor/artifacts/shot.png)](https://cursor.com)"))
            .containsExactly(MediaSegment.Image("/opt/cursor/artifacts/shot.png", "Shot"))
        assertThat(MediaMarkup.split("<a href=\"https://x.test\"><img src=\"x.png\"></a> tail"))
            .containsExactly(MediaSegment.Image("x.png", null), MediaSegment.Text("tail")).inOrder()
        // A text link is left for the inline renderer — stripping the tags would drop the URL.
        assertThat(MediaMarkup.split("See <a href=\"https://x.test/p\">PR #1</a>."))
            .containsExactly(MediaSegment.Text("See <a href=\"https://x.test/p\">PR #1</a>.")).inOrder()
    }

    @Test
    fun `attributes are case insensitive, single quoted or bare, and entities decode`() {
        val segments = MediaMarkup.split("<IMG SRC='https://h.test/a.png?x=1&amp;y=2' ALT=Hi>")
        assertThat(segments).containsExactly(MediaSegment.Image("https://h.test/a.png?x=1&y=2", "Hi"))
    }

    @Test
    fun `text that only looks like markup stays text`() {
        assertThat(MediaMarkup.split("Use ![ for images and <img> needs a src")).containsExactly(
            MediaSegment.Text("Use ![ for images and needs a src"),
        )
        assertThat(MediaMarkup.split("plain paragraph")).containsExactly(MediaSegment.Text("plain paragraph"))
        assertThat(MediaMarkup.containsMedia("plain paragraph")).isFalse()
        assertThat(MediaMarkup.containsMedia("x <img src=a.png>")).isTrue()
    }

    @Test
    fun `stripped replaces media by alt text for previews`() {
        assertThat(MediaMarkup.stripped("<img alt=\"Sidebar\" src=\"a.png\" /> then <video src=\"v.mp4\"></video> words"))
            .isEqualTo("Sidebar then words")
        assertThat(MediaMarkup.stripped("<img src=\"a.png\" />")).isEmpty()
    }

    @Test
    fun `trimPartialTail hides an unfinished tag while streaming`() {
        assertThat(MediaMarkup.trimPartialTail("Proof: <img alt=\"x\" src=\"/opt/cursor/art")).isEqualTo("Proof:")
        assertThat(MediaMarkup.trimPartialTail("Proof: ![Web vs And")).isEqualTo("Proof:")
        assertThat(MediaMarkup.trimPartialTail("Proof: <video src=\"a.mp4\">")).isEqualTo("Proof:")
        assertThat(MediaMarkup.trimPartialTail("Proof: <img src=\"a.png\" />")).isEqualTo("Proof: <img src=\"a.png\" />")
        assertThat(MediaMarkup.trimPartialTail("Proof: ![x](a.png)")).isEqualTo("Proof: ![x](a.png)")
        assertThat(MediaMarkup.trimPartialTail("plain")).isEqualTo("plain")
    }

    @Test
    fun `trimPartialTail reaches back as far as an unfinished tag or image does`() {
        val prose = "word ".repeat(2_000)
        // No `>` since the tag opened: everything from it is hidden, however far back it is.
        assertThat(MediaMarkup.trimPartialTail("Intro <img alt=\"a < b\" $prose")).isEqualTo("Intro")
        // No `]` since the image opened, or no `)` since its `](`.
        assertThat(MediaMarkup.trimPartialTail("Intro ![a) $prose")).isEqualTo("Intro")
        assertThat(MediaMarkup.trimPartialTail("Intro ![a]($prose")).isEqualTo("Intro")
        // A long run of attributes before the `>` of a video whose body is still short.
        assertThat(MediaMarkup.trimPartialTail("Intro <video title=\"$prose\">clip")).isEqualTo("Intro")
    }

    @Test
    fun `trimPartialTail gives an unclosed video 160 characters of body, plus a final line break`() {
        val open = "Intro <video src=\"a.mp4\">"
        assertThat(MediaMarkup.trimPartialTail(open + "x".repeat(160))).isEqualTo("Intro")
        assertThat(MediaMarkup.trimPartialTail(open + "x".repeat(160) + "\n")).isEqualTo("Intro")
        assertThat(MediaMarkup.trimPartialTail(open + "x".repeat(160) + "\r\n")).isEqualTo("Intro")
        assertThat(MediaMarkup.trimPartialTail(open + "x".repeat(161))).isEqualTo(open + "x".repeat(161))
        assertThat(MediaMarkup.trimPartialTail(open + "x>".repeat(80))).isEqualTo("Intro")
        assertThat(MediaMarkup.trimPartialTail(open + "x>".repeat(81))).isEqualTo(open + "x>".repeat(81))
    }

    @Test
    fun `trimPartialTail matches a whole-text scan on every prefix of random markup`() {
        val fragments = listOf(
            "<img", "<IMG", "<video", "<Video ", "<audio", "<a href=\"x\">", "<source src=\"s.mp4\">", "</video>", "</AUDIO>",
            "<", ">", "/>", "![", "]", "](", "(", ")", "!", "[", " src=\"a.png\"", " alt='x'", "\n", "\r\n", "\r", "\u2028",
            " ", "a", "word ", "img", "video",
        )
        val random = kotlin.random.Random(8)
        repeat(400) {
            val text = buildString {
                repeat(random.nextInt(1, 40)) {
                    if (random.nextInt(6) == 0) {
                        append("xy> ]) ".random(random).toString().repeat(random.nextInt(0, 220)))
                    } else {
                        append(fragments.random(random))
                    }
                }
            }
            for (end in 0..text.length) {
                val partial = text.substring(0, end)
                assertWithMessage("prefix %s", partial).that(MediaMarkup.trimPartialTail(partial)).isEqualTo(wholeScanTrim(partial))
            }
        }
    }

    @Test
    fun `trimPartialTail matches a whole-text scan while a long reply streams`() {
        val reply = buildString {
            repeat(60) { i ->
                append("Paragraph $i with `code` and a [link](https://x.test/$i) (aside) -> next.\n\n")
                when (i % 6) {
                    0 -> append("<img alt=\"Shot $i\" src=\"/opt/cursor/artifacts/s$i.png\" />\n\n")
                    1 -> append("![Diagram $i](https://x.test/d$i.png \"title\")\n\n")
                    2 -> append("<video controls poster=\"p.png\">\n<source src=\"clip$i.mp4\" type=\"video/mp4\">\nNo video.\n</video>\n\n")
                    3 -> append("> quoted a < b and [![Badge](b$i.svg)](https://x.test)\n\n")
                    else -> append("- item\n- item [x]\n\n")
                }
            }
        }
        for (end in 0..reply.length) {
            val partial = reply.substring(0, end)
            assertWithMessage("prefix of length %s", end).that(MediaMarkup.trimPartialTail(partial)).isEqualTo(wholeScanTrim(partial))
        }
    }

    /** The trim as it was first written: the tail pattern searched for from the text's first character. */
    private fun wholeScanTrim(text: String): String {
        val m = wholeScanTail.find(text) ?: return text
        return text.substring(0, m.range.first).trimEnd()
    }

    private val wholeScanTail = Regex(
        """(?:<img\b[^>]*|<(?:video|audio)\b[^>]*|<(?:video|audio)\b[^>]*>(?:(?!</(?:video|audio)>).){0,160}|!\[[^\]]*(?:]\([^)]*)?)$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}

class ArtifactPathsTest {

    @Test
    fun `vm paths map to the relative form the download endpoint accepts`() {
        assertThat(ArtifactPaths.apiPath("/opt/cursor/artifacts/screenshots/demo.png")).isEqualTo("artifacts/screenshots/demo.png")
        assertThat(ArtifactPaths.apiPath("opt/cursor/artifacts/demo.mp4")).isEqualTo("artifacts/demo.mp4")
        assertThat(ArtifactPaths.apiPath("artifacts/demo.png")).isEqualTo("artifacts/demo.png")
        assertThat(ArtifactPaths.apiPath("./artifacts/demo.png")).isEqualTo("artifacts/demo.png")
        assertThat(ArtifactPaths.apiPath("/workspace/artifacts/a/b.png")).isEqualTo("artifacts/a/b.png")
    }

    @Test
    fun `anything else is not an artifact`() {
        assertThat(ArtifactPaths.apiPath("https://example.com/artifacts/x.png")).isNull()
        assertThat(ArtifactPaths.apiPath("screenshots/06_conversation.png")).isNull()
        assertThat(ArtifactPaths.apiPath("/opt/cursor/artifacts/../etc/passwd")).isNull()
        assertThat(ArtifactPaths.apiPath("/opt/cursor/artifacts/")).isNull()
        assertThat(ArtifactPaths.apiPath("data:image/png;base64,AAAA")).isNull()
    }

    @Test
    fun `fileName strips directories and query strings`() {
        assertThat(ArtifactPaths.fileName("artifacts/screenshots/demo.png")).isEqualTo("demo.png")
        assertThat(ArtifactPaths.fileName("https://s3.test/bucket/clip.mp4?X-Amz-Signature=abc")).isEqualTo("clip.mp4")
    }
}

class MediaRefTest {

    @Test
    fun `sources resolve to remote, artifact, inline or unavailable references`() {
        assertThat(MediaRef.parse("https://cdn.test/a.png", "bc-1")).isEqualTo(MediaRef.Remote("https://cdn.test/a.png"))
        assertThat(MediaRef.parse("/opt/cursor/artifacts/a.png", "bc-1")).isEqualTo(MediaRef.Artifact("bc-1", "artifacts/a.png"))
        // No agent to ask (rendered outside a conversation): the VM path cannot be fetched.
        assertThat(MediaRef.parse("/opt/cursor/artifacts/a.png", null)).isEqualTo(MediaRef.Unavailable("/opt/cursor/artifacts/a.png"))
        // A path of the agent's own machine or repository: read from its workspace, else its repository at its branch.
        assertThat(MediaRef.parse("docs/diagram.png", "bc-1")).isEqualTo(MediaRef.Workspace("bc-1", "docs/diagram.png"))
        assertThat(MediaRef.parse("/workspace/screenshots/45.png", "bc-1")).isEqualTo(MediaRef.Workspace("bc-1", "/workspace/screenshots/45.png"))
        assertThat(MediaRef.parse("docs/diagram.png", null)).isEqualTo(MediaRef.Unavailable("docs/diagram.png"))
        assertThat(MediaRef.parse("#figure-2", "bc-1")).isEqualTo(MediaRef.Unavailable("#figure-2"))
        assertThat(MediaRef.parse("mailto:a@b.c", "bc-1")).isEqualTo(MediaRef.Unavailable("mailto:a@b.c"))

        val inline = MediaRef.parse("data:image/png;base64,iVBORw0KGgo=", "bc-1")
        assertThat(inline).isInstanceOf(MediaRef.Inline::class.java)
        inline as MediaRef.Inline
        assertThat(inline.mimeType).isEqualTo("image/png")
        assertThat(inline.bytes.toList()).containsExactly(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()).inOrder()
    }

    @Test
    fun `cache keys identify the artifact rather than its rotating url`() {
        val a = MediaRef.Artifact("bc-1", "artifacts/a.png")
        assertThat(a.cacheKey).isEqualTo("artifact:bc-1:artifacts/a.png")
        assertThat(a.label).isEqualTo("a.png")
        assertThat(MediaRef.Remote("https://s3.test/x/clip.mp4?sig=1").label).isEqualTo("clip.mp4")
    }
}
