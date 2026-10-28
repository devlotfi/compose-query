# Caveats and limitations

Behaviors worth knowing before you ship, with workarounds.

## Behaviors

### Every composition entry triggers a fetch

`LaunchedEffect(queryKey)` calls `fetchQuery()` each time a `rememberQuery` enters composition (or its key changes). There is no `staleTime`, so cached data is shown immediately **and** a background refetch always follows. `refetchOnResume = false` does not prevent this.

*Workaround:* if a query must not refetch on re-entry, wrap it in your own logic (for example only compose it when `queryClient.cache.store[key] == null`, or fetch once in the ViewModel).

### Null means error

A `null` return from `queryFn` or `mutateFn` becomes `Exception("Empty")`. Return `Unit`/`true` for void endpoints. For mutations, `onError` is **not** called for this particular case, only `error` is set.

### Failed refetch keeps old data

When a refetch fails, `data` keeps the previous value and `error` is set. Handle `error != null && data != null` explicitly (banner/snackbar) if you want users to know the data may be stale.

### Mutation callbacks stop when the screen leaves

`onSuccess`/`onError` are cleared when the composable that created the mutation leaves composition, but the mutation itself keeps running. Put critical side effects (invalidation, persistence) inside `mutateFn`. See [Mutations](03-mutations.md#callback-lifetime-important).

### `onSuccess` exceptions are reported as mutation errors

`onSuccess` runs inside the same `try` block as `mutateFn`. If it throws, the exception is caught, stored in `error`, and `onError` is called, even though the write succeeded. Keep `onSuccess` safe, or wrap risky calls in `runCatching`.

### `mutate` is dropped while running

Calls to `mutate` during an active run are silently ignored (not queued). Use distinct `mutateKey`s, including an item id, for independent concurrent mutations.

### Mutation `data` is not cleared

`data` holds the last successful result until the next success. It isn't reset when a new run starts.

### Key change does not cancel the old request

Switching `queryKey` leaves the previous request running and caching its result. This is intentional, but it means rapid key changes (typing) can create many requests. Debounce the value before it enters the key.

### Same key means same type

The cache stores `Any?` and casts unchecked. Using one key with two different `T`s throws `ClassCastException`. Centralize keys (see [Recipes](05-recipes.md#reusable-query-hooks)).

### `queryFn` closures

The latest `queryFn` lambda is always used, including for refetches triggered from elsewhere (`queryClient.refetchQueries`). If a query is not currently composed, its last captured lambda is used. Prefer putting every input in the key and reading it from the `queryKey` parameter.

### Invalidation only reaches registered queries

`refetchQueries` / `resetQueries` act on keys that have been composed at least once in this client. Cache-only entries written via `cache.store` aren't affected.

### `resetQueries` refetches immediately

It clears data and starts a fetch. For logout, prefer discarding the whole ViewModel/client so no request is made with old credentials.

### Stores are never garbage collected

`queryDataStore`, `mutationDataStore` and `cache.store` grow with the number of distinct keys until the ViewModel is cleared. For unbounded keys (search terms, infinite pagination), scope the client to a screen or remove entries manually:

```kotlin
queryClient.cache.store.remove(key)
queryClient.queryDataStore.remove(key)
```

### Not thread-safe

The stores are plain `mutableMapOf`. Call client functions from the main thread. `viewModelScope` already does so.

## Not supported (yet)

| TanStack Query feature | Status | Workaround |
|---|---|---|
| `enabled` (conditional queries) | Not available | Compose the query conditionally; see [dependent queries](05-recipes.md#dependent-queries) |
| `staleTime` / `gcTime` | Not available | Manage the cache manually |
| Automatic retries / backoff | Not available | Retry inside `queryFn` with a loop and `delay` |
| Infinite queries | Not available | Page number in the key, or accumulate pages yourself |
| `placeholderData` / keep previous data | Not available | Remember the last non-null `data` in the UI |
| `onSuccess` receiving data/variables | Not available | Read `mutation.data`, or do the work in `mutateFn` |
| Mutation `reset` | Not available | Use a new `mutateKey`, or ignore `data` in the UI |
| Persistence across process death | Not available | Seed `QueryCache` from disk at startup |
| Refetch on network reconnect | Not available | Call `refetchQueries` from a connectivity callback |
| Polling (`refetchInterval`) | Not available | `LaunchedEffect` loop calling `refetch` |

### Retry example

```kotlin
suspend fun <T> retry(times: Int = 3, initialDelay: Long = 500, block: suspend () -> T): T {
    var delayMs = initialDelay
    repeat(times - 1) {
        try { return block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { delay(delayMs); delayMs *= 2 }
    }
    return block()
}

queryFn { retry { api.getTodos() } }
```

### Polling example

```kotlin
val status = queryClient.rememberQuery<Status> {
    queryKey = listOf("status")
    queryFn { api.status() }
}

LaunchedEffect(Unit) {
    while (true) {
        delay(10_000)
        status.refetch()
    }
}
```

### Refetch on reconnect example

```kotlin
val connectivity = LocalContext.current.getSystemService<ConnectivityManager>()!!
DisposableEffect(Unit) {
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            queryClient.refetchQueries(emptyList(), exact = false)
        }
    }
    connectivity.registerDefaultNetworkCallback(callback)
    onDispose { connectivity.unregisterNetworkCallback(callback) }
}
```
