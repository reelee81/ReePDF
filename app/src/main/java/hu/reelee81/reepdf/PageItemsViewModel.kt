package hu.reelee81.reepdf

import androidx.lifecycle.ViewModel

class PageItemsViewModel : ViewModel() {
    val pageItems = mutableListOf<PageItem>()

    override fun onCleared() {
        super.onCleared()
        for (item in pageItems) {
            item.thumbnail?.recycle()
        }
        pageItems.clear()
    }
}
