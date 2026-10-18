package com.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

@DslMarker
annotation class MutationDsl

@MutationDsl
class MutationConfig<T, S> {
    var refetchOnResume: Boolean = true
    internal var mutateFn: (suspend (params: S) -> T?)? = null
    internal var onSuccess: (suspend () -> Unit)? = null
    internal var onError: (suspend (e: Exception) -> Unit)? = null


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
fun <T, S> rememberMutation(block: MutationConfig<T, S>.() -> Unit): RememberMutationReturn<T, S> {
    val config = MutationConfig<T, S>().apply(block)
    val mutateFn = requireNotNull(config.mutateFn)
    val onSuccess = requireNotNull(config.onSuccess)
    val onError = requireNotNull(config.onError)

    val scope = rememberCoroutineScope()
    val (loading, setLoading) = remember { mutableStateOf<Boolean>(false) }
    val (error, setError) = remember { mutableStateOf<Exception?>(null) }
    val (data, setData) = remember { mutableStateOf<T?>(null) }

    val mutate = fun(params: S) {
        scope.launch {
            setLoading(true);
            try {
                val res = mutateFn(params)
                if (res == null) setError(Exception("Empty"));
                setData(res)
                onSuccess()
            } catch (e: Exception) {
                setError(e)
                onError(e)
            }
            setLoading(false);
        }
    }

    return RememberMutationReturn(
        loading,
        error,
        data,
        mutate
    )
}