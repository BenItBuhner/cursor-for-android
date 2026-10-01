package com.cursorforandroid.domain

/**
 * The New Chat page's Projects as last arranged there: every one of them first to last ([order], which the sidebar's
 * Projects group follows too; see [LocalAgentState.projectOrder]) and the ones dragged below its "Hidden" line
 * ([hidden]; see [LocalAgentState.hiddenProjectIds]).
 */
data class ProjectArrangement(val order: List<String>, val hidden: Set<String>)

/**
 * The New Chat page's Project shortcuts in their two sections, each first to last: [shown] above the "Hidden" line and
 * [hidden] below it. Pure, so the page's drags and accessibility moves are the same moves.
 */
data class ProjectSections(val shown: List<String>, val hidden: List<String>) {

    enum class Section { Shown, Hidden }

    /** A place in one of the sections. */
    data class Place(val section: Section, val index: Int)

    operator fun get(section: Section): List<String> = if (section == Section.Shown) shown else hidden

    /** Where [id] is, or null when it is in neither section. */
    fun placeOf(id: String): Place? {
        val shownAt = shown.indexOf(id)
        if (shownAt >= 0) return Place(Section.Shown, shownAt)
        val hiddenAt = hidden.indexOf(id)
        return if (hiddenAt >= 0) Place(Section.Hidden, hiddenAt) else null
    }

    /** [id] taken out of its place and put at [to], its index clamped to the section's room. Unchanged when [id] is in neither. */
    fun moved(id: String, to: Place): ProjectSections {
        if (placeOf(id) == null) return this
        val shown = shown.toMutableList().apply { remove(id) }
        val hidden = hidden.toMutableList().apply { remove(id) }
        val target = if (to.section == Section.Shown) shown else hidden
        target.add(to.index.coerceIn(0, target.size), id)
        return ProjectSections(shown, hidden)
    }

    /** [id] moved to the end of the other section: hidden if it was shown, shown if it was hidden. */
    fun toggled(id: String): ProjectSections {
        val place = placeOf(id) ?: return this
        val other = if (place.section == Section.Shown) Section.Hidden else Section.Shown
        return moved(id, Place(other, this[other].size))
    }

    /**
     * The whole order these sections make of [base] (every Project, first to last, as it stood): each slot [base] gives
     * a shown Project goes to the next of [shown], each it gives a hidden one to the next of [hidden]. Within a section
     * the order is the page's; how the two interleave — which the sidebar, listing them all, shows — is [base]'s, so
     * hiding a Project here does not move it there. An id of [base] neither section names keeps its slot; one only
     * the sections name comes last.
     */
    fun order(base: List<String>): List<String> {
        val hiddenIds = hidden.toHashSet()
        val named = hiddenIds + shown
        val nextShown = shown.iterator()
        val nextHidden = hidden.iterator()
        val out = ArrayList<String>(base.size)
        for (id in base) {
            val next = when (id) {
                !in named -> null
                in hiddenIds -> nextHidden
                else -> nextShown
            }
            when {
                next == null -> out += id
                next.hasNext() -> out += next.next()
            }
        }
        nextShown.forEachRemaining { out += it }
        nextHidden.forEachRemaining { out += it }
        return out
    }

    /** What to save of these sections, given the order every Project stood in before ([base]). */
    fun arrangement(base: List<String>): ProjectArrangement = ProjectArrangement(order(base), hidden.toSet())

    companion object {
        /**
         * The sections of [ids] (every Project, first to last) with [hidden] below the line; or, while the page holds an
         * arrangement not yet saved ([arranged]), that arrangement's — a Project it does not name (one made since) on
         * the side [hidden] puts it, ahead of the arranged ones, as [AgentListOrganizer.arranged] places it.
         */
        fun of(ids: List<String>, hidden: Set<String>, arranged: ProjectSections? = null): ProjectSections {
            if (arranged == null) return ProjectSections(ids.filter { it !in hidden }, ids.filter { it in hidden })
            val rank = HashMap<String, Int>()
            (arranged.shown + arranged.hidden).forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
            val ordered = ids.sortedBy { rank[it] ?: -1 }
            val arrangedHidden = arranged.hidden.toHashSet()
            val arrangedShown = arranged.shown.toHashSet()
            fun isHidden(id: String) = id in arrangedHidden || (id !in arrangedShown && id in hidden)
            return ProjectSections(ordered.filterNot(::isHidden), ordered.filter(::isHidden))
        }
    }
}
