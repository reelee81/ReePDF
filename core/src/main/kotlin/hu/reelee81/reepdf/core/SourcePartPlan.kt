package hu.reelee81.reepdf.core

import kotlin.math.min

class SourcePartPlan(val parts: List<Part>) {
    sealed interface Part {
        data class Keep(val part: Int) : Part
        data class Write(val replaces: Int?, val pieces: List<Piece>) : Part
    }

    sealed interface Piece {
        val pages: Int

        data class Pages(val first: Int, val last: Int) : Piece {
            override val pages: Int get() = last - first + 1
        }

        data class Incoming(val input: Int, val first: Int, val last: Int) : Piece {
            override val pages: Int get() = last - first + 1
        }

        data class Replacement(val key: Int) : Piece {
            override val pages: Int get() = 1
        }

        data object EmptyPage : Piece {
            override val pages: Int get() = 1
        }
    }

    val writtenPages: Int get() = parts.sumOf { part -> if (part is Part.Write) part.pieces.sumOf { it.pages } else 0 }

    fun pageCounts(layout: SourcePartLayout): List<Int> = parts.map { part ->
        when (part) {
            is Part.Keep -> layout.pageCounts[part.part]
            is Part.Write -> part.pieces.sumOf { it.pages }
        }
    }

    companion object {
        const val PART_PAGES = 1000

        fun unchanged(layout: SourcePartLayout): SourcePartPlan = SourcePartPlan(List(layout.parts) { Part.Keep(it) })

        fun append(layout: SourcePartLayout, pieces: List<Piece>, partPages: Int = PART_PAGES): SourcePartPlan {
            if (pieces.sumOf { it.pages } == 0) return unchanged(layout)
            val last = layout.parts - 1
            val refill = last >= 0 && layout.pageCounts[last] < partPages
            val parts = ArrayList<Part>()
            for (part in 0 until if (refill) last else layout.parts) parts += Part.Keep(part)

            var replaces: Int? = null
            var current = ArrayList<Piece>()
            var currentPages = 0
            if (refill) {
                replaces = last
                current += Piece.Pages(layout.start(last), layout.end(last))
                currentPages = layout.pageCounts[last]
            }
            for (piece in pieces) {
                var rest: Piece? = piece
                while (rest != null) {
                    if (currentPages == partPages) {
                        parts += Part.Write(replaces, current)
                        replaces = null
                        current = ArrayList()
                        currentPages = 0
                    }
                    val (taken, remaining) = split(rest, partPages - currentPages)
                    current += taken
                    currentPages += taken.pages
                    rest = remaining
                }
            }
            if (current.isNotEmpty()) parts += Part.Write(replaces, current)
            return SourcePartPlan(parts)
        }

        fun removePages(layout: SourcePartLayout, removed: Set<Int>): SourcePartPlan {
            val touched = removed.filter { it in 0 until layout.total }.mapTo(HashSet()) { layout.partOf(it) }
            val parts = ArrayList<Part>()
            for (part in 0 until layout.parts) {
                if (part !in touched) {
                    parts += Part.Keep(part)
                    continue
                }
                val pieces = ArrayList<Piece>()
                for (page in layout.start(part)..layout.end(part)) {
                    if (page !in removed) addPage(pieces, page)
                }
                if (pieces.isNotEmpty()) parts += Part.Write(part, pieces)
            }
            return SourcePartPlan(parts)
        }

        fun reorder(layout: SourcePartLayout, newToOld: IntArray): SourcePartPlan {
            require(newToOld.size == layout.total) { "The reorder has ${newToOld.size} pages, the source ${layout.total}" }
            require(newToOld.sorted() == newToOld.indices.toList()) { "The reorder must move every page exactly once" }
            val first = newToOld.indices.firstOrNull { newToOld[it] != it } ?: return unchanged(layout)
            val last = newToOld.indices.last { newToOld[it] != it }
            val parts = ArrayList<Part>()
            for (part in 0 until layout.parts) {
                if (layout.end(part) < first || layout.start(part) > last) {
                    parts += Part.Keep(part)
                    continue
                }
                val pieces = ArrayList<Piece>()
                for (place in layout.start(part)..layout.end(part)) addPage(pieces, newToOld[place])
                parts += Part.Write(part, pieces)
            }
            return SourcePartPlan(parts)
        }

        fun rewritePages(layout: SourcePartLayout, pages: Set<Int>): SourcePartPlan {
            val touched = pages.filter { it in 0 until layout.total }.mapTo(HashSet()) { layout.partOf(it) }
            return SourcePartPlan(List(layout.parts) { part ->
                if (part in touched) Part.Write(part, listOf(Piece.Pages(layout.start(part), layout.end(part)))) else Part.Keep(part)
            })
        }

        fun replaceGroups(layout: SourcePartLayout, perGroup: Int, groups: Set<Int>): SourcePartPlan {
            val per = perGroup.coerceAtLeast(1)
            val replaced = groups.filter { it >= 0 && it.toLong() * per < layout.total }.toSet()
            val touched = HashSet<Int>()
            for (group in replaced) {
                val start = group * per
                val end = min(layout.total - 1, start + per - 1)
                for (part in layout.partOf(start)..layout.partOf(end)) touched += part
            }
            val parts = ArrayList<Part>()
            for (part in 0 until layout.parts) {
                if (part !in touched) {
                    parts += Part.Keep(part)
                    continue
                }
                val pieces = ArrayList<Piece>()
                for (page in layout.start(part)..layout.end(part)) {
                    val group = page / per
                    when {
                        group !in replaced -> addPage(pieces, page)
                        page == group * per -> pieces += Piece.Replacement(group)
                    }
                }
                if (pieces.isNotEmpty()) parts += Part.Write(part, pieces)
            }
            return SourcePartPlan(parts)
        }

        private fun addPage(pieces: MutableList<Piece>, page: Int) {
            val previous = pieces.lastOrNull()
            if (previous is Piece.Pages && previous.last + 1 == page) {
                pieces[pieces.lastIndex] = Piece.Pages(previous.first, page)
            } else {
                pieces += Piece.Pages(page, page)
            }
        }

        private fun split(piece: Piece, room: Int): Pair<Piece, Piece?> {
            if (piece.pages <= room) return piece to null
            return when (piece) {
                is Piece.Pages -> Piece.Pages(piece.first, piece.first + room - 1) to Piece.Pages(piece.first + room, piece.last)
                is Piece.Incoming -> Piece.Incoming(piece.input, piece.first, piece.first + room - 1) to
                    Piece.Incoming(piece.input, piece.first + room, piece.last)
                is Piece.Replacement, Piece.EmptyPage -> piece to null
            }
        }
    }
}
