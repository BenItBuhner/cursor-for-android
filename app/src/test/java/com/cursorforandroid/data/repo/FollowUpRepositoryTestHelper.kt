package com.cursorforandroid.data.repo

import kotlinx.coroutines.Job
import java.lang.reflect.Field

/** Test-only view of [FollowUpRepository] internals. */
internal object FollowUpRepositoryTestHelper {
    const val MAX_ENTRIES = 24

    fun entryCount(repo: FollowUpRepository): Int {
        val field: Field = FollowUpRepository::class.java.getDeclaredField("entries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(repo) as Map<*, *>).size
    }

    fun dispatcherActive(repo: FollowUpRepository, agentId: String): Boolean =
        entry(repo, agentId)?.let { entry ->
            val field = entry.javaClass.getDeclaredField("dispatcher").apply { isAccessible = true }
            (field.get(entry) as Job?)?.isActive == true
        } == true

    private fun entry(repo: FollowUpRepository, agentId: String): Any? {
        val field: Field = FollowUpRepository::class.java.getDeclaredField("entries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(repo) as Map<*, *>)[agentId]
    }
}
