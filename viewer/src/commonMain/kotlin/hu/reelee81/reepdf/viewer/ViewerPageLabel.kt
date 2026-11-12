package hu.reelee81.reepdf.viewer

sealed interface ViewerPageLabel {
    data class Single(val page: Int, val total: Int) : ViewerPageLabel

    data class Range(val first: Int, val last: Int, val total: Int) : ViewerPageLabel

    companion object {
        fun of(pages: IntRange, total: Int): ViewerPageLabel =
            if (pages.first == pages.last) Single(pages.first + 1, total)
            else Range(pages.first + 1, pages.last + 1, total)
    }
}
