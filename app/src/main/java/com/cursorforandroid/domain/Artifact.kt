package com.cursorforandroid.domain

/**
 * One file an agent published under `/opt/cursor/artifacts/` (screenshots, recordings, anything it saved for the
 * user), as `GET /v1/agents/{id}/artifacts` lists it. [path] is the `artifacts/…` form the download endpoint takes.
 */
data class Artifact(
    val path: String,
    val sizeBytes: Long,
    val updatedAtMillis: Long?,
) {
    val name: String get() = ArtifactPaths.fileName(path)

    /** The file's extension, lowercased, without the dot; empty when it has none. */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val kind: Kind
        get() = when (extension) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg" -> Kind.Image
            "mp4", "webm", "mov", "mkv", "m4v" -> Kind.Video
            "md", "markdown" -> Kind.Markdown
            "txt", "log", "json", "yaml", "yml", "xml", "csv", "html", "css", "js", "ts", "kt", "java", "py", "sh", "toml", "diff", "patch" -> Kind.Text
            else -> Kind.Other
        }

    /** The VM path the agent wrote to, for matching a reply's `<img src>` or a `recordScreen` result against the list. */
    val vmPath: String get() = ArtifactPaths.VM_ROOT + path.removePrefix("artifacts/")

    enum class Kind { Image, Video, Markdown, Text, Other }
}
