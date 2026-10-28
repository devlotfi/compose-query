# QueryClient and cache

The `QueryClient` is the hub. It holds every active `QueryData` and `MutationData`, owns the coroutine scope, and exposes invalidation functions.

```kotlin
class QueryClient(
    val scope: CoroutineScope,
    val cache: QueryCache,
)
```

## Anatomy

| Piece | Type | Role |
|---|---|---|
| `scope` | `CoroutineScope` | Runs all fetches and mutations. |
| `cache` | `QueryCache` | `store: MutableMap<List<Any?>, Any?>`, the last successful value per key. |
| `queryDataStore` | `MutableMap<List<Any?>, QueryData<*>>` | One `QueryData` (state + job) per query key that has been composed. |
| `mutationDataStore` | `MutableMap<List<Any?>, MutationData<*, *>>` | One `MutationData` per mutation key. |

### Why a separate `QueryCache`?

`QueryData` holds *state and jobs*; `QueryCache` holds *values*. Keeping them apart means:

- You can create a `QueryClient` per screen/session but share one `QueryCache`.
- You can pre-populate the cache before a screen is shown.
- You can swap the cache for tests.

```kotlin
val sharedCache = QueryCache()

val clientA = QueryClient(scopeA, sharedCache)
val clientB = QueryClient(scopeB, sharedCache)   // sees clientA's cached values
```

New `QueryData` instances read their initial `data` from the cache, so `loading` starts as `false` when a value already exists.

## Invalidation

### `refetchQueries(queryKey, exact = true)`

Starts a background refetch (same as calling `refetch`). Existing data stays visible; no `loading` flash.

```kotlin
// Exactly ["todos"]
queryClient.refetchQueries(listOf("todos"))

// Every key starting with "todos": ["todos"], ["todos", 5], ["todos", "list", "done", 1] ...
queryClient.refetchQueries(listOf("todos"), exact = false)
```

### `resetQueries(queryKey, exact = true)`

Clears the cached value, sets `data = null`, and refetches. The UI returns to the `loading` state.

```kotlin
queryClient.resetQueries(listOf("profile"))
queryClient.resetQueries(listOf("search"), exact = false)
```

### Exact vs prefix matching

| Call | Matches |
|---|---|
| `refetchQueries(listOf("todos"))` | only `["todos"]` |
| `refetchQueries(listOf("todos"), exact = false)` | `["todos"]`, `["todos", 1]`, `["todos", "list", 2]` |
| `refetchQueries(listOf("todos", 1), exact = false)` | `["todos", 1]`, `["todos", 1, "comments"]` |
| `refetchQueries(emptyList(), exact = false)` | **every** registered query |

Prefix matching compares list elements in order using `==`.

### Only mounted-at-least-once queries are matched

Invalidation walks `queryDataStore`, which is filled when a query is first composed. If a key was **never composed** in this client, there's nothing to refetch. It will fetch normally when it is first composed.

### Which should I use?

| Goal | Use |
|---|---|
| Data changed on the server, refresh quietly | `refetchQueries` |
| Data is invalid to show (account switch, permissions change) | `resetQueries` |
| User asked to reload | `refetch` / `fetchInteractively` on the query result |

## Typical mutation + invalidation flow

```kotlin
val renameTodo = queryClient.rememberMutation<Todo, Pair<Int, String>> {
    mutateKey = listOf("todos", "rename")
    mutateFn { (id, title) ->
        val updated = api.rename(id, title)
        // refresh the list AND the detail query
        queryClient.refetchQueries(listOf("todos"), exact = false)
        updated
    }
}
```

Because both `["todos"]` and `["todos", id]` share the `"todos"` prefix, a single call refreshes both.

## Direct cache access

The cache is a plain map and is public. This is useful for pre-filling, inspecting, or clearing.

```kotlin
// Read
val cachedTodos = queryClient.cache.store[listOf("todos")] as? List<Todo>

// Pre-populate before the screen opens (e.g. from a list item to its detail page)
queryClient.cache.store[listOf("todos", todo.id)] = todo
```

When `TodoDetail` composes for `["todos", todo.id]`, it finds the value and starts with `loading = false`, then refetches in the background.

> Writing to `cache.store` does not notify already-composed queries. It only affects `QueryData` instances created **after** the write. To update a live query, set `data` on its `QueryData` (below).

## Updating a live query manually

`getQueryData(key, init)` returns the `QueryData` for a key, creating it with `init` if missing. Setting `data` updates both the Compose state and the cache, and every composable observing that key recomposes.

```kotlin
fun QueryClient.updateTodos(transform: (List<Todo>) -> List<Todo>) {
    val queryData = getQueryData<List<Todo>>(listOf("todos")) {
        QueryData(
            queryClient = this,
            queryKey = listOf("todos"),
            queryFn = { api.getTodos() },
        )
    }
    queryData.data?.let { queryData.data = transform(it) }
}

// Mark a todo as done instantly, without waiting for the network
queryClient.updateTodos { list ->
    list.map { if (it.id == id) it.copy(done = true) else it }
}
```

This is the foundation for [optimistic updates](recipes.md#optimistic-updates).

## Lifecycle and scope

| Event | Effect |
|---|---|
| Composable leaves composition | In-flight requests **continue**; results still update the cache. |
| Composable re-enters | Cached data shows instantly; a refetch starts. |
| `ViewModel.onCleared()` | `viewModelScope` is cancelled, which cancels all jobs. The client and its stores are garbage collected with the ViewModel. |
| Configuration change | ViewModel survives, so the client, cache, and in-flight requests survive. |
| Process death | Everything is lost. The cache is in-memory only. |

### Choosing the ViewModel scope

| ViewModel scope | Cache lives as long as | Good for |
|---|---|---|
| Activity | The activity | App-wide shared data |
| Navigation graph | The nested graph's back stack entry | A flow (checkout, onboarding) |
| Single screen | That screen's back stack entry | Isolated screens that shouldn't share data |

## Keeping the client alive across composables

`rememberQueryDataInQueryClient` and `rememberMutationDataInQueryClient` are lower-level building blocks used internally by `rememberQuery`/`rememberMutation`. They return a delegate that looks up (or creates) the data object in the client:

```kotlin
val queryData by queryClient.rememberQueryDataInQueryClient(key) {
    QueryData<T>(queryClient, key, queryFn)
}
```

You normally never need them. Reach for them only when building your own higher-level abstractions (for example a custom `rememberInfiniteQuery`).

Next: [Recipes](recipes.md)
