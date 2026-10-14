package com.devlotfi.composequery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

data class RememberMutationReturn<T, S>(
    val loading: Boolean,
    val error: Exception?,
    val data: T?,
    val mutate: (params: S) -> Unit
)

@Composable
fun <T, S> rememberMutation(
    mutateFn: suspend (params: S) -> T?,
    onSuccess: (suspend () -> Unit)? = null,
    onError: (suspend (e: Exception) -> Unit)? = null
): RememberMutationReturn<T, S> {
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
                if (onSuccess != null) onSuccess()
            } catch (e: Exception) {
                setError(e)
                if (onError != null) onError(e)
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