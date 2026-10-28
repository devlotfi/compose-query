package com.github.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty


class QueryData<T>(
    val queryClient: QueryClient,
    val queryKey: List<Any?>,
    var queryFn: suspend (queryKey: List<Any?>) -> T?,
) {
    var requestId: Int = 0
    var job: Job? = null
    var loading by mutableStateOf(queryClient.cache.store[queryKey] == null)
    var fetching by mutableStateOf(queryClient.cache.store[queryKey] == null)
    var interactiveFetching by mutableStateOf(false)
    var error by mutableStateOf<Exception?>(null)

    @Suppress("UNCHECKED_CAST")
    var _data by mutableStateOf(queryClient.cache.store[queryKey] as T?)
    var data: T?
        get() = _data
        set(value) {
            _data = value
            queryClient.cache.store.set(queryKey, value)
        }

    fun fetchQuery() {
        job?.cancel()
        val currentRequestId = ++requestId
        job = queryClient.scope.launch {
            error = null
            fetching = true
            loading = data == null
            try {
                val res = queryFn(queryKey)
                ensureActive()
                if (res == null) {
                    error = Exception("Empty")
                } else {
                    data = res
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                if (currentRequestId == requestId) {
                    fetching = false
                    interactiveFetching = false
                    loading = false
                }
            }
        }
    }

    fun refetch() {
        fetchQuery()
    }

    fun fetchInteractively() {
        interactiveFetching = true
        fetchQuery()
    }

    fun reset() {
        queryClient.cache.store.remove(queryKey)
        data = null
        fetchQuery()
    }

    class RememberInQueryClient<T>(
        private val key: List<Any?>,
        private val queryClient: QueryClient,
        private val init: () -> QueryData<T>,
    ) : ReadOnlyProperty<Any?, QueryData<T>>, EntryKey {

        override fun getValue(
            thisRef: Any?,
            property: KProperty<*>
        ): QueryData<T> =
            queryClient.getQueryData(key, init)
    }
}

@DslMarker
annotation class QueryDsl

@QueryDsl
class QueryConfig<T> {
    var refetchOnResume: Boolean = true
    var queryKey: List<Any?>? = null
    internal var queryFn: (suspend (queryKey: List<Any?>) -> T?)? = null
    fun queryFn(block: suspend (queryKey: List<Any?>) -> T?) {
        queryFn = block
    }
}

data class RememberQueryReturn<T>(
    val loading: Boolean,
    val fetching: Boolean,
    val interactiveFetching: Boolean,
    val error: Exception?,
    val data: T?,
    val refetch: () -> Unit,
    val fetchInteractively: () -> Unit,
    val reset: () -> Unit
)

@Suppress("UNCHECKED_CAST")
@Composable
fun <T> QueryClient.rememberQuery(
    block: QueryConfig<T>.() -> Unit
): RememberQueryReturn<T> {
    val config = QueryConfig<T>().apply(block)
    val queryKey = requireNotNull(config.queryKey)
    val queryFn = requireNotNull(config.queryFn)
    val refetchOnResume = config.refetchOnResume

    val skipResume = remember { booleanArrayOf(true) }
    val queryData by rememberQueryDataInQueryClient(queryKey) {
        QueryData<T>(
            queryClient = this,
            queryKey = queryKey,
            queryFn = queryFn
        )
    }

    SideEffect { queryData.queryFn = queryFn }

    LaunchedEffect(queryKey) {
        queryData.fetchQuery()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (skipResume[0]) {
            skipResume[0] = false
            return@LifecycleEventEffect
        }
        if (refetchOnResume) queryData.refetch()
    }

    return RememberQueryReturn(
        loading = queryData.loading,
        fetching = queryData.fetching,
        interactiveFetching = queryData.interactiveFetching,
        error = queryData.error,
        data = queryData.data,
        refetch = queryData::refetch,
        fetchInteractively = queryData::fetchInteractively,
        reset = queryData::reset
    )
}