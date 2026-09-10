package com.cursorforandroid.data.repo

import java.lang.reflect.Field

/** Test-only view of [FollowUpRepository] internals. */
internal object FollowUpRepositoryTestHelper {
    const val MAX_ENTRIES = 24

    fun entryCount(repo: FollowUpRepository): Int {
        val field: Field = FollowUpRepository::class.java.getDeclaredField("entries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(repo) as Map<*, *>).size
    }
}
