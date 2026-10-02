package com.cursorforandroid.promo

import java.io.File

/** Where the capture reads its script from and writes its footage to, and how it was asked to run. */
object Promo {
    val root: File get() = File(System.getProperty("promo.root") ?: error("promo.root is unset: run through promo/capture/run.sh"))

    val out: File get() = File(System.getProperty("promo.out") ?: File(root, "capture/out").path).apply { mkdirs() }

    /** Every Nth frame saved as a PNG and no video: a quick look at a scene. 0 captures the video. */
    val preview: Int get() = System.getProperty("promo.preview")?.toIntOrNull() ?: 0

    /** Stops a scene after this many frames; for trying the start of one. */
    val frameLimit: Int get() = System.getProperty("promo.frames")?.toIntOrNull() ?: Int.MAX_VALUE
}
