package com.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.devlotfi.composeref.rememberRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch


@DslMarker
annotation class QueryDsl

@QueryDsl
class QueryConfig<T> {
    var refetchOnResume: Boolean = true
    var queryKey: List<Any?>? = null
    internal var queryFn: (suspend () -> T?)? = null
    fun queryFn(block: suspend () -> T?) {
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
fun <T> rememberQuery(block: QueryConfig<T>.() -> Unit): RememberQueryReturn<T> {
    val config = QueryConfig<T>().apply(block)
    val queryKey = requireNotNull(config.queryKey)
    val queryFn = requireNotNull(config.queryFn)
    val refetchOnResume = config.refetchOnResume

    val localQuery = LocalQuery.current
    val scope = rememberCoroutineScope()
    val jobRef = rememberRef<Job?>(null)
    val requestCounter = rememberRef(0)
    val skipNextResume = rememberRef(true)
    val initialCache = remember { localQuery.getCache(queryKey) }
    var loading by remember { mutableStateOf<Boolean>(initialCache == null) }
    var fetching by remember { mutableStateOf<Boolean>(initialCache == null) }
    var interactiveFetching by remember { mutableStateOf<Boolean>(false) }
    var error by remember { mutableStateOf<Exception?>(null) }
    var data by remember { mutableStateOf<T?>(localQuery.getCache(queryKey) as? T?) }

    fun fetchQuery() {
        jobRef.current?.cancel()
        val requestId = ++requestCounter.current
        jobRef.current = scope.launch {
            data = localQuery.getCache(queryKey) as? T?
            error = null
            fetching = true
            loading = data == null
            try {
                val res = queryFn()
                ensureActive()
                if (res == null) error = Exception("Empty");
                data = res
                localQuery.setCache(queryKey, res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            } finally {
                if (requestId == requestCounter.current) {
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
        data = null
        fetchQuery()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (skipNextResume.current) {
            skipNextResume.current = false
            return@LifecycleEventEffect
        }
        if (refetchOnResume) refetch()
    }

    DisposableEffect(queryKey, queryFn) {
        val handler = fun(action: NotifyAction) {
            when (action) {
                NotifyAction.Refetch -> {
                    refetch()
                }

                NotifyAction.Reset -> {
                    reset()
                }

                NotifyAction.DataChanged -> {
                    data = localQuery.getCache(queryKey) as? T?
                }
            }
        }

        localQuery.subscribe(queryKey, handler)

        fetchQuery()

        onDispose {
            localQuery.unsubscribe(queryKey, handler)
        }
    }

    return RememberQueryReturn(
        loading = loading,
        fetching = fetching,
        interactiveFetching = interactiveFetching,
        error = error,
        data = data,
        refetch = { refetch() },
        fetchInteractively = { fetchInteractively() },
        reset = { reset() }
    )
}