package com.cursorforandroid.util

import androidx.compose.runtime.Composer
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.RecomposeScopeObserver
import androidx.compose.runtime.tooling.observe
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Counts every composable body of the app that runs (is not skipped), by function name, through the trace markers
 * the Compose compiler puts in debug builds; no production code is touched. [install] before composing, [uninstall]
 * after the test: the tracer is process-wide and cannot be removed, only switched off.
 *
 * It sees composable functions only; [RecomposeScopes] also sees the content lambdas inside them.
 */
@OptIn(InternalComposeTracingApi::class)
object RecomposeCounter : CompositionTracer {
    private val counts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var on = false

    override fun isTraceInProgress(): Boolean = on

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        val name = info.substringBefore(" (")
        if (name.startsWith(APP_PREFIX)) counts.merge(name.removePrefix(APP_PREFIX), 1, Int::plus)
    }

    override fun traceEventEnd() = Unit

    fun install() {
        Composer.setTracer(this)
        counts.clear()
        on = true
    }

    fun uninstall() {
        on = false
        counts.clear()
    }

    fun reset() = counts.clear()

    fun snapshot(): Map<String, Int> = HashMap(counts)

    /** Runs of the composables whose name is [name], e.g. `AgentRowItem` for `ui.agents.AgentRowItem`. */
    fun count(name: String): Int = counts.entries.filter { it.key == name || it.key.endsWith(".$name") }.sumOf { it.value }

    fun top(n: Int = 20): String = counts.entries.sortedByDescending { it.value }.take(n).joinToString(", ") { "${it.key}=${it.value}" }

    private const val APP_PREFIX = "com.cursorforandroid."
}

/**
 * Recompose scopes that run, named by the class of the lambda each one restarts (the composable or content lambda it
 * belongs to). Hand it to `currentComposer.composition.observe` at the root of the content; [observeAll] then also
 * catches the scopes a changed parameter reruns, which the invalidation map alone never names.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class RecomposeScopes : CompositionObserver, RecomposeScopeObserver {
    var scopes = 0
        private set
    val byName = HashMap<String, Int>()
    private val observed = Collections.newSetFromMap(IdentityHashMap<RecomposeScope, Boolean>())

    override fun onBeginComposition(composition: Composition, invalidationMap: Map<RecomposeScope, Set<Any>?>) {
        for (scope in invalidationMap.keys) if (observed.add(scope)) scope.observe(this)
    }
    override fun onEndComposition(composition: Composition) = Unit
    override fun onBeginScopeComposition(scope: RecomposeScope) {
        scopes++
        val name = nameOf(scope)
        byName[name] = (byName[name] ?: 0) + 1
    }
    override fun onEndScopeComposition(scope: RecomposeScope) = Unit
    override fun onScopeDisposed(scope: RecomposeScope) { observed.remove(scope) }

    /** Observes every scope in [data]'s groups, not only the invalidated ones, so children recomposed by a changed parameter count too. */
    fun observeAll(data: CompositionData) {
        fun walk(g: CompositionGroup) {
            for (d in g.data) {
                if (d is RecomposeScope && observed.add(d)) d.observe(this)
                subcompositions(d).forEach { sub -> sub.compositionGroups.forEach(::walk) }
            }
            for (c in g.compositionGroups) walk(c)
        }
        for (g in data.compositionGroups) walk(g)
    }

    fun reset() { scopes = 0; byName.clear() }

    /** Runs of the scopes whose name contains [part]. */
    fun count(part: String): Int = byName.entries.filter { part in it.key }.sumOf { it.value }

    fun top(n: Int = 40): String = byName.entries.sortedByDescending { it.value }.take(n).joinToString("\n") { "  ${it.value}x ${it.key}" }

    /** The slot tables of subcompositions (BoxWithConstraints, LazyColumn items…) hanging off a remembered context. */
    private fun subcompositions(d: Any?): List<CompositionData> = runCatching {
        var x: Any? = d ?: return emptyList()
        if (x!!.javaClass.name.endsWith("RememberObserverHolder")) x = x.javaClass.getDeclaredField("wrapped").apply { isAccessible = true }.get(x)
        if (x == null || !x.javaClass.name.endsWith("CompositionContextHolder")) return emptyList()
        val ref = x.javaClass.getDeclaredField("ref").apply { isAccessible = true }.get(x)
        val composers = ref.javaClass.getDeclaredField("composers").apply { isAccessible = true }.get(ref) as Collection<*>
        composers.mapNotNull { (it as? Composer)?.compositionData }
    }.getOrDefault(emptyList())

    private fun nameOf(scope: RecomposeScope): String = runCatching {
        val f = scope.javaClass.getDeclaredField("block").apply { isAccessible = true }
        var b: Any? = f.get(scope)
        if (b != null && b.javaClass.name.startsWith("androidx.compose.runtime.internal.ComposableLambdaImpl")) {
            val outer = if (b.javaClass.name.contains("\$invoke\$")) b.javaClass.declaredFields.firstOrNull { it.type.name.contains("ComposableLambdaImpl") }?.apply { isAccessible = true }?.get(b) else b
            b = outer?.javaClass?.getDeclaredField("_block")?.apply { isAccessible = true }?.get(outer) ?: b
        }
        val c = b?.javaClass ?: return@runCatching "?"
        // Hidden lambda classes carry no source name; what they capture says which lambda they are.
        if (c.name.contains("\$\$Lambda")) c.name.substringBefore("\$\$Lambda") + "{" + c.declaredFields.joinToString(",") { it.type.simpleName } + "}" else c.name
    }.getOrDefault("?")
}

/** Bytes the calling thread has allocated so far, or 0 on a JVM that does not say. */
fun threadAllocatedBytes(): Long = runCatching {
    val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes").invoke(bean) as Long
}.getOrDefault(0L)
