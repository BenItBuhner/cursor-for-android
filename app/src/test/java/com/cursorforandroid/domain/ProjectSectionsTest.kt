package com.cursorforandroid.domain

import com.cursorforandroid.domain.ProjectSections.Place
import com.cursorforandroid.domain.ProjectSections.Section
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The New Chat page's two sections of Project shortcuts, and what of them is saved, without a page. */
class ProjectSectionsTest {

    private val ids = listOf("a", "b", "c", "d", "e")

    @Test
    fun `the hidden Projects sit under the line in the order they come in`() {
        val sections = ProjectSections.of(ids, hidden = setOf("d", "b"))
        assertThat(sections.shown).containsExactly("a", "c", "e").inOrder()
        assertThat(sections.hidden).containsExactly("b", "d").inOrder()
    }

    @Test
    fun `hiding one leaves every Project's place in the whole order, which the sidebar lists, as it was`() {
        val hidden = ProjectSections.of(ids, emptySet()).moved("c", Place(Section.Hidden, 0))
        assertThat(hidden.shown).containsExactly("a", "b", "d", "e").inOrder()
        assertThat(hidden.hidden).containsExactly("c")
        val saved = hidden.arrangement(ids)
        assertThat(saved.order).containsExactly(*ids.toTypedArray()).inOrder()
        assertThat(saved.hidden).containsExactly("c")

        val shownAgain = ProjectSections.of(saved.order, saved.hidden).moved("c", Place(Section.Shown, 2))
        assertThat(shownAgain.arrangement(saved.order)).isEqualTo(ProjectArrangement(ids, emptySet()))
    }

    @Test
    fun `a reorder across the line keeps each section in the page's order and the interleaving in the old one's`() {
        // Shown a, c, e; hidden b, d. Drop e between b and d, then move a after c.
        val start = ProjectSections.of(ids, setOf("b", "d"))
        val next = start.moved("e", Place(Section.Hidden, 1)).moved("a", Place(Section.Shown, 1))
        assertThat(next.shown).containsExactly("c", "a").inOrder()
        assertThat(next.hidden).containsExactly("b", "e", "d").inOrder()
        val order = next.order(ids)
        assertThat(order.filter { it in next.hidden }).containsExactly("b", "e", "d").inOrder()
        assertThat(order.filterNot { it in next.hidden }).containsExactly("c", "a").inOrder()
        assertThat(order).containsExactly("c", "b", "a", "e", "d").inOrder()
    }

    @Test
    fun `an order saved from a stale base still has exactly the sections the page shows`() {
        val first = ProjectSections.of(ids, emptySet()).moved("d", Place(Section.Shown, 0))
        // The list has not caught up with the first drop when the second comes.
        val second = first.moved("a", Place(Section.Hidden, 0))
        val order = second.order(ids)
        assertThat(ProjectSections.of(order, second.hidden.toSet())).isEqualTo(second)
    }

    @Test
    fun `an arrangement not saved yet is shown over the page's own, a Project made since on its own side and first`() {
        val arranged = ProjectSections(shown = listOf("c", "a"), hidden = listOf("b"))
        val sections = ProjectSections.of(listOf("new", "a", "b", "c", "hiddenNew"), hidden = setOf("hiddenNew", "a"), arranged = arranged)
        assertThat(sections.shown).containsExactly("new", "c", "a").inOrder()
        assertThat(sections.hidden).containsExactly("hiddenNew", "b").inOrder()
    }

    @Test
    fun `a move clamps into its section, and a toggle sends a Project to the other section's end`() {
        val sections = ProjectSections(listOf("a", "b"), listOf("c"))
        assertThat(sections.moved("a", Place(Section.Hidden, 9))).isEqualTo(ProjectSections(listOf("b"), listOf("c", "a")))
        assertThat(sections.moved("c", Place(Section.Shown, -3))).isEqualTo(ProjectSections(listOf("c", "a", "b"), emptyList()))
        assertThat(sections.toggled("c")).isEqualTo(ProjectSections(listOf("a", "b", "c"), emptyList()))
        assertThat(sections.toggled("a")).isEqualTo(ProjectSections(listOf("b"), listOf("c", "a")))
        assertThat(sections.moved("zz", Place(Section.Shown, 0))).isSameInstanceAs(sections)
    }
}
