package com.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class NotifyAction {
    Refetch,
    Reset,
    DataChanged
}

data class CacheEntry(
    var value: Any?,
    val observers: MutableList<(action: NotifyAction) -> Unit> = mutableListOf()
)

data class QueryProviderData(
    val setCache: (queryKey: List<Any?>, value: Any?) -> Unit,
    val getCache: (queryKey: List<Any?>) -> Any?,
    val subscribe: (queryKey: List<Any?>, handler: (action: NotifyAction) -> Unit) -> Unit,
    val unsubscribe: (queryKey: List<Any?>, handler: (action: NotifyAction) -> Unit) -> Unit,
    val refetchQueries: (queryKey: List<Any?>, exact: Boolean) -> Unit,
    val resetQueries: (queryKey: List<Any?>, exact: Boolean) -> Unit
)

val LocalQuery = compositionLocalOf<QueryProviderData> { error("Not init") }

class QueryProviderViewModel : ViewModel() {
    private val lock = Any()
    private val cache = HashMap<List<Any?>, CacheEntry>()

    private fun entryFor(key: List<Any?>): CacheEntry =
        cache.getOrPut(key) { CacheEntry(value = null) } // call only under lock

    // true if this key begins with every element of prefix, in order
    private fun List<Any?>.startsWith(prefix: List<Any?>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix

    // call only under lock
    private fun matchingEntries(queryKey: List<Any?>, exact: Boolean): List<CacheEntry> {
        return if (exact) {
            listOfNotNull(cache[queryKey])
        } else {
            cache.entries
                .filter { it.key.startsWith(queryKey) }
                .map { it.value }
        }
    }

    fun setCache(queryKey: List<Any?>, value: Any?) {
        val observers = synchronized(lock) {
            val entry = entryFor(queryKey)
            entry.value = value
            return@synchronized entry.observers.toList()
        }
        notify(observers, NotifyAction.DataChanged)
    }

    fun getCache(queryKey: List<Any?>): Any? =
        synchronized(lock) {
            return@synchronized cache[queryKey]?.value
        }

    fun subscribe(queryKey: List<Any?>, handler: (NotifyAction) -> Unit) {
        synchronized(lock) {
            val entry = entryFor(queryKey)
            if (!entry.observers.contains(handler)) entry.observers.add(handler)
        }
    }

    fun unsubscribe(queryKey: List<Any?>, handler: (NotifyAction) -> Unit) {
        synchronized(lock) { cache[queryKey]?.observers?.remove(handler) }
    }

    fun refetchQueries(queryKey: List<Any?>, exact: Boolean = true) {
        val observers = synchronized(lock) {
            matchingEntries(queryKey, exact).flatMap { it.observers }
        }
        notify(observers, NotifyAction.Refetch)
    }

    fun resetQueries(queryKey: List<Any?>, exact: Boolean = true) {
        val observers = synchronized(lock) {
            matchingEntries(queryKey, exact).flatMap { entry ->
                entry.value = null
                entry.observers
            }
        }
        notify(observers, NotifyAction.Reset)
    }

    private fun notify(observers: List<(NotifyAction) -> Unit>, action: NotifyAction) {
        if (observers.isEmpty()) return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            observers.forEach { it(action) }
        }
    }
}

@Composable
fun QueryProvider(
    viewModel: QueryProviderViewModel = viewModel(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalQuery provides QueryProviderData(
            setCache = viewModel::setCache,
            getCache = viewModel::getCache,
            subscribe = viewModel::subscribe,
            unsubscribe = viewModel::unsubscribe,
            refetchQueries = viewModel::refetchQueries,
            resetQueries = viewModel::resetQueries
        )
    ) {
        content()
    }
}