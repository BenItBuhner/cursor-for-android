package com.cursorforandroid.audit

import java.io.File

object AuditLog {
    private val file = File("/tmp/audit/probe.log").also { it.parentFile.mkdirs() }

    fun line(text: String) {
        println(text)
        file.appendText(text + "\n")
    }
}
