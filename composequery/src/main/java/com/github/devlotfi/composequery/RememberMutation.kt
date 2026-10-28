package com.github.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class MutationData<T, S>(
    val queryClient: QueryClient,
    var mutateFn: suspend (params: S) -> T?,
    var onSuccess: (suspend () -> Unit)? = null,
    var onError: (suspend (e: Exception) -> Unit)? = null,
) {
    var job: Job? = null
    var loading by mutableStateOf(false)
    var error by mutableStateOf<Exception?>(null)
    var data by mutableStateOf<T?>(null)

    fun mutate(params: S) {
        if (job?.isActive == true) return
        job?.cancel()
        job = queryClient.scope.launch {
            error = null
            loading = true
            try {
                val res = mutateFn(params)
                if (res == null) {
                    error = Exception("Empty")
                } else {
                    data = res
                    if (onSuccess != null) onSuccess?.invoke()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                if (onError != null) onError?.invoke(e)
            } finally {
                loading = false
            }
        }
    }

    class RememberInQueryClient<T, S>(
        private val key: List<Any?>,
        private val queryClient: QueryClient,
        private val init: () -> MutationData<T, S>,
    ) : ReadOnlyProperty<Any?, MutationData<T, S>>, EntryKey {

        override fun getValue(
            thisRef: Any?,
            property: KProperty<*>
        ): MutationData<T, S> =
            queryClient.getMutationData(key, init)
    }
}

@DslMarker
annotation class MutationDsl

@MutationDsl
class MutationConfig<T, S> {
    var mutateKey: List<Any?>? = null
    var mutateFn: (suspend (params: S) -> T?)? = null
    var onSuccess: (suspend () -> Unit)? = { }
    var onError: (suspend (e: Exception) -> Unit)? = { }

    fun mutateFn(block: suspend (params: S) -> T?) {
        mutateFn = block
    }

    fun onSuccess(block: suspend () -> Unit) {
        onSuccess = block
    }

    fun onError(block: suspend (e: Exception) -> Unit) {
        onError = block
    }
}

data class RememberMutationReturn<T, S>(
    val loading: Boolean,
    val error: Exception?,
    val data: T?,
    val mutate: (params: S) -> Unit
)

@Composable
fun <T, S> QueryClient.rememberMutation(
    block: MutationConfig<T, S>.() -> Unit
): RememberMutationReturn<T, S> {
    val config = MutationConfig<T, S>().apply(block)
    val mutateKey = requireNotNull(config.mutateKey)
    val mutateFn = requireNotNull(config.mutateFn)
    val onSuccess = requireNotNull(config.onSuccess)
    val onError = requireNotNull(config.onError)

    val mutationData by rememberMutationDataInQueryClient(mutateKey) {
        MutationData<T, S>(
            queryClient = this,
            mutateFn = mutateFn,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    SideEffect {
        mutationData.mutateFn = mutateFn
        mutationData.onSuccess = onSuccess
        mutationData.onError = onError
    }
    DisposableEffect(mutationData) {
        onDispose {
            mutationData.onSuccess = null
            mutationData.onError = null
        }
    }

    return RememberMutationReturn(
        mutationData.loading,
        mutationData.error,
        mutationData.data,
        mutate = mutationData::mutate
    )
}