package com.github.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope

class QueryClient(
    val scope: CoroutineScope,
    val cache: QueryCache
) {
    val queryDataStore = mutableMapOf<List<Any?>, QueryData<*>>()
    val mutationDataStore = mutableMapOf<List<Any?>, MutationData<*, *>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> getQueryData(key: List<Any?>, init: () -> QueryData<T>): QueryData<T> =
        queryDataStore.getOrPut(key) { init() } as QueryData<T>

    @Suppress("UNCHECKED_CAST")
    fun <T, S> getMutationData(
        key: List<Any?>,
        init: () -> MutationData<T, S>
    ): MutationData<T, S> =
        mutationDataStore.getOrPut(key) { init() } as MutationData<T, S>

    @Composable
    fun <T> rememberQueryDataInQueryClient(
        key: List<Any?>,
        init: () -> QueryData<T>,
    ): QueryData.RememberInQueryClient<T> = remember(key, this) {
        QueryData.RememberInQueryClient(key, this, init)
    }

    @Composable
    fun <T, S> rememberMutationDataInQueryClient(
        key: List<Any?>,
        init: () -> MutationData<T, S>,
    ): MutationData.RememberInQueryClient<T, S> = remember(key, this) {
        MutationData.RememberInQueryClient(key, this, init)
    }

    private fun matchingKeys(queryKey: List<Any?>, exact: Boolean): List<List<Any?>> =
        if (exact) listOfNotNull(queryKey.takeIf { it in queryDataStore })
        else queryDataStore.keys.filter { it.startsWith(queryKey) }

    fun refetchQueries(queryKey: List<Any?>, exact: Boolean = true) {
        matchingKeys(queryKey, exact).forEach { queryDataStore[it]?.refetch() }
    }

    fun resetQueries(queryKey: List<Any?>, exact: Boolean = true) {
        matchingKeys(queryKey, exact).forEach { queryDataStore[it]?.reset() }
    }

    private fun List<Any?>.startsWith(prefix: List<Any?>): Boolean =
        size >= prefix.size && subList(0, prefix.size) == prefix
}