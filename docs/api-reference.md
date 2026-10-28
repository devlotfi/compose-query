# API reference

Package: `com.github.devlotfi.composequery`

## `QueryCache`

```kotlin
class QueryCache {
    val store: MutableMap<List<Any?>, Any?>
}
```

In-memory map from query key to last successful value. Public and mutable. Not thread-safe (use from the main thread).

## `QueryClient`

```kotlin
class QueryClient(val scope: CoroutineScope, val cache: QueryCache)
```

| Member | Signature | Description |
|---|---|---|
| `scope` | `CoroutineScope` | Scope used for every fetch and mutation. |
| `cache` | `QueryCache` | The value cache. |
| `queryDataStore` | `MutableMap<List<Any?>, QueryData<*>>` | Registered queries. |
| `mutationDataStore` | `MutableMap<List<Any?>, MutationData<*, *>>` | Registered mutations. |
| `getQueryData` | `fun <T> getQueryData(key, init: () -> QueryData<T>): QueryData<T>` | Get or create a `QueryData`. Unchecked cast to `T`. |
| `getMutationData` | `fun <T, S> getMutationData(key, init): MutationData<T, S>` | Get or create a `MutationData`. |
| `rememberQueryDataInQueryClient` | `@Composable fun <T> (key, init): QueryData.RememberInQueryClient<T>` | Delegate used with `by`. Remembered on `(key, client)`. |
| `rememberMutationDataInQueryClient` | `@Composable fun <T, S> (key, init): MutationData.RememberInQueryClient<T, S>` | Same for mutations. |
| `refetchQueries` | `fun (queryKey: List<Any?>, exact: Boolean = true)` | Background refetch of matching registered queries. |
| `resetQueries` | `fun (queryKey: List<Any?>, exact: Boolean = true)` | Clear cache + data, then refetch matching queries. |

Matching: `exact = true` matches the key itself if registered; `exact = false` matches every registered key that **starts with** `queryKey` (an empty list matches all).

## `QueryClient.rememberQuery`

```kotlin
@Composable
fun <T> QueryClient.rememberQuery(block: QueryConfig<T>.() -> Unit): RememberQueryReturn<T>
```

### `QueryConfig<T>`

| Member | Type | Default |
|---|---|---|
| `queryKey` | `List<Any?>?` | required |
| `queryFn(block)` | `suspend (queryKey: List<Any?>) -> T?` | required |
| `refetchOnResume` | `Boolean` | `true` |

### `RememberQueryReturn<T>`

| Property | Type |
|---|---|
| `loading` | `Boolean` |
| `fetching` | `Boolean` |
| `interactiveFetching` | `Boolean` |
| `error` | `Exception?` |
| `data` | `T?` |
| `refetch` | `() -> Unit` |
| `fetchInteractively` | `() -> Unit` |
| `reset` | `() -> Unit` |

It is a `data class`, so it supports destructuring, but named access is clearer.

### Side effects performed by `rememberQuery`

- `SideEffect`: keeps `queryFn` up to date with the latest lambda.
- `LaunchedEffect(queryKey)`: calls `fetchQuery()` on entering composition and whenever the key changes.
- `LifecycleEventEffect(ON_RESUME)`: calls `refetch()` if `refetchOnResume` (the first resume is skipped).

## `QueryData<T>`

```kotlin
class QueryData<T>(
    val queryClient: QueryClient,
    val queryKey: List<Any?>,
    var queryFn: suspend (queryKey: List<Any?>) -> T?,
)
```

| Member | Description |
|---|---|
| `loading`, `fetching`, `interactiveFetching`, `error` | Compose snapshot state. |
| `data` | Compose state. Setting it also writes to `queryClient.cache.store[queryKey]`. |
| `fetchQuery()` | Cancels the current job, starts a new fetch. Only the latest request updates the flags. |
| `refetch()` | Alias of `fetchQuery()`. |
| `fetchInteractively()` | Sets `interactiveFetching = true`, then `fetchQuery()`. |
| `reset()` | Removes the cache entry, sets `data = null`, then `fetchQuery()`. |

Fetch flow: `error = null` → `fetching = true` → `loading = (data == null)` → run `queryFn` → on `null` result set `error = Exception("Empty")`, otherwise set `data` → in `finally`, if this is still the latest request, clear `fetching`, `interactiveFetching` and `loading`.

## `QueryClient.rememberMutation`

```kotlin
@Composable
fun <T, S> QueryClient.rememberMutation(block: MutationConfig<T, S>.() -> Unit): RememberMutationReturn<T, S>
```

### `MutationConfig<T, S>`

| Member | Type | Default |
|---|---|---|
| `mutateKey` | `List<Any?>?` | required |
| `mutateFn(block)` | `suspend (params: S) -> T?` | required |
| `onSuccess(block)` | `suspend () -> Unit` | no-op |
| `onError(block)` | `suspend (e: Exception) -> Unit` | no-op |

### `RememberMutationReturn<T, S>`

| Property | Type |
|---|---|
| `loading` | `Boolean` |
| `error` | `Exception?` |
| `data` | `T?` |
| `mutate` | `(params: S) -> Unit` |

### Side effects performed by `rememberMutation`

- `SideEffect`: keeps `mutateFn`, `onSuccess`, `onError` up to date.
- `DisposableEffect(mutationData)`: on dispose, sets `onSuccess` and `onError` to `null`.

## `MutationData<T, S>`

| Member | Description |
|---|---|
| `loading`, `error`, `data` | Compose snapshot state. |
| `mutate(params)` | No-op if a job is active. Otherwise runs `mutateFn`; sets `data` and calls `onSuccess` on success, sets `error` and calls `onError` on failure. A `null` result sets `error = Exception("Empty")` without calling `onError`. |

## Other types

| Type | Description |
|---|---|
| `EntryKey` | Small interface with `getKey(key, property)` helper, implemented by the `RememberInQueryClient` delegates. Internal plumbing. |
| `QueryDsl`, `MutationDsl` | `@DslMarker` annotations preventing accidental access to an outer config from a nested block. |
| `QueryData.RememberInQueryClient`, `MutationData.RememberInQueryClient` | `ReadOnlyProperty` delegates that look up the data object in the client on each read. |
