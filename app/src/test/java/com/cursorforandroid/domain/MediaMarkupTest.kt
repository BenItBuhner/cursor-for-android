package com.cursorforandroid.domain

import com.google.common.truth.Truth.assertThat
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
        assertThat(MediaRef.parse("docs/diagram.png", "bc-1")).isEqualTo(MediaRef.Unavailable("docs/diagram.png"))

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
