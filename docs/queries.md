# Queries

A query reads data (usually from a server) and tracks its lifecycle as Compose state.

```kotlin
val result = queryClient.rememberQuery<T> {
    queryKey = listOf(...)
    queryFn { key -> ... }       // returns T?
    refetchOnResume = true       // optional
}
```

## Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `queryKey` | `List<Any?>` | **required** | Identifies this query in the client and the cache. |
| `queryFn { }` | `suspend (queryKey: List<Any?>) -> T?` | **required** | Fetches the data. Receives the query key. Return `null` to signal "no data" (treated as an error). |
| `refetchOnResume` | `Boolean` | `true` | Refetch when the lifecycle reaches `ON_RESUME` (app returns to foreground, screen resumes). The very first resume is skipped because the initial fetch already runs. |

Missing `queryKey` or `queryFn` throws `IllegalArgumentException` (via `requireNotNull`).

## Return value

`rememberQuery` returns a `RememberQueryReturn<T>`:

| Property | Type | Meaning |
|---|---|---|
| `loading` | `Boolean` | `true` only while there is **no data yet** and a fetch is running. First load, or after `reset()`. |
| `fetching` | `Boolean` | `true` whenever **any** fetch is in flight, including background refetches. |
| `interactiveFetching` | `Boolean` | `true` while a fetch started via `fetchInteractively()` is in flight. Use it for pull-to-refresh spinners. |
| `error` | `Exception?` | The last error, or `null`. Cleared when a new fetch starts. |
| `data` | `T?` | The last successful result, or `null` if none yet. Kept while refetching and after a failed refetch. |
| `refetch` | `() -> Unit` | Starts a background refetch. |
| `fetchInteractively` | `() -> Unit` | Like `refetch`, but also sets `interactiveFetching = true`. |
| `reset` | `() -> Unit` | Clears cached data for this key, then fetches again (shows `loading` again). |

## The state matrix

| Situation | `loading` | `fetching` | `data` | `error` |
|---|---|---|---|---|
| First load, no cache | `true` | `true` | `null` | `null` |
| First load succeeded | `false` | `false` | value | `null` |
| First load failed | `false` | `false` | `null` | exception |
| Revisit with cache | `false` | `true`, then `false` | cached value | `null` |
| Background refetch succeeded | `false` | `true`, then `false` | new value | `null` |
| Background refetch failed | `false` | `true`, then `false` | **old value kept** | exception |
| After `reset()` | `true` | `true` | `null` | `null` |
| `queryFn` returned `null` | `false` | `false` | unchanged | `Exception("Empty")` |

Rule of thumb:

- Use **`loading`** to decide whether to show a full-screen skeleton or spinner.
- Use **`fetching`** to show a subtle "refreshing" indicator (e.g. a thin progress bar).
- Use **`error` together with `data`** to decide between a blocking error screen (`data == null`) or a non-blocking banner (`data != null`).

## Rendering pattern

```kotlin
@Composable
fun <T> QueryContent(
    query: RememberQueryReturn<T>,
    content: @Composable (T) -> Unit,
) {
    when {
        query.loading -> LoadingView()
        query.data == null -> ErrorView(
            message = query.error?.message ?: "Unknown error",
            onRetry = query.refetch,
        )
        else -> {
            Column {
                if (query.fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (query.error != null) ErrorBanner(query.error!!.message)
                content(query.data!!)
            }
        }
    }
}
```

Usage:

```kotlin
val profile = queryClient.rememberQuery<Profile> {
    queryKey = listOf("profile")
    queryFn { api.getProfile() }
}

QueryContent(profile) { ProfileCard(it) }
```

## Query keys

A key is a `List<Any?>`. Two keys are the same query if the lists are **equal** (`==`). The key is also how you target queries for invalidation (see [QueryClient and cache](query-client-and-cache.md)).

```kotlin
listOf("todos")                       // all todos
listOf("todos", todoId)               // one todo
listOf("todos", "list", status, page) // filtered, paginated list
listOf("user", userId, "posts")
```

Guidelines:

- Put the **broadest** identifier first and get more specific. This makes prefix invalidation (`exact = false`) work: `listOf("todos")` matches all of the above that start with `"todos"`.
- Only use values with proper `equals`: primitives, strings, enums, `data class`es. **Don't** put lambdas, plain classes without `equals`, or mutable lists in keys.
- Include **every input** `queryFn` depends on. If it isn't in the key, a change won't refetch and caches will collide.

## Queries with parameters

`queryFn` receives the key, so you can read parameters from it and keep the function independent from captured state:

```kotlin
@Composable
fun TodoDetail(todoId: Int) {
    val todo = LocalQueryClient.current.rememberQuery<Todo> {
        queryKey = listOf("todos", todoId)
        queryFn { key ->
            val id = key[1] as Int
            api.getTodo(id)
        }
    }
    // ...
}
```

Capturing `todoId` directly also works, because the library keeps the latest `queryFn` lambda up to date on every recomposition:

```kotlin
queryKey = listOf("todos", todoId)
queryFn { api.getTodo(todoId) }
```

Both are fine as long as `todoId` is part of the key.

### What happens when the key changes

When `todoId` changes from `1` to `2`:

1. `rememberQuery` switches to the `QueryData` for `["todos", 2]` (creating it if needed).
2. If `["todos", 2]` is already cached, `data` is available immediately and `loading = false`.
3. A fetch for the new key starts (`LaunchedEffect(queryKey)`).
4. The request for key `1` is **not cancelled**. It finishes in the background and updates its own cache entry. Revisiting `1` later shows fresh data.

## Refetching

### Manual refetch

```kotlin
Button(onClick = todos.refetch) { Text("Reload") }
```

Starts a fetch. Any in-flight request for the same query is cancelled and replaced. Only the most recent request is allowed to update `fetching`/`loading` flags.

### Refetch on resume

Enabled by default. Disable it for data that rarely changes or is expensive:

```kotlin
val countries = queryClient.rememberQuery<List<Country>> {
    queryKey = listOf("countries")
    refetchOnResume = false
    queryFn { api.getCountries() }
}
```

> Note that every time a composable **enters composition**, a fetch is started (`LaunchedEffect(queryKey)`), regardless of `refetchOnResume`. `refetchOnResume` only controls refetching when an already-composed screen resumes. See [Caveats](caveats-and-limitations.md).

### Interactive refetch (pull-to-refresh)

`fetchInteractively` sets `interactiveFetching = true` so you can distinguish *user-initiated* refreshes from background ones. Background refetches (on resume, invalidation) never flip `interactiveFetching`, so a pull-to-refresh spinner won't appear unexpectedly.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen() {
    val todos = LocalQueryClient.current.rememberQuery<List<Todo>> {
        queryKey = listOf("todos")
        queryFn { api.getTodos() }
    }

    PullToRefreshBox(
        isRefreshing = todos.interactiveFetching,
        onRefresh = todos.fetchInteractively,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(todos.data.orEmpty()) { TodoRow(it) }
        }
    }
}
```

### Reset

`reset()` removes the cached value, sets `data = null`, and fetches again, so the UI goes back to the `loading` state. Use it when stale data would be **wrong** to show (e.g. after switching accounts), as opposed to `refetch`, which keeps showing old data.

```kotlin
TextButton(onClick = profile.reset) { Text("Reload from scratch") }
```

## Null results and errors

- If `queryFn` **throws** (other than cancellation), the exception lands in `error`.
- If `queryFn` **returns `null`**, `error` becomes `Exception("Empty")`.
- Cancellation (`CancellationException`) is rethrown, never reported as an error.

An empty list is **not** null, so `emptyList()` is valid data. For nullable endpoints, map them to a non-null wrapper:

```kotlin
queryFn { api.findUser(id) ?: throw NotFoundException() }
```

## Threading

The client's scope is typically `viewModelScope`, which uses `Dispatchers.Main.immediate`. Retrofit/Ktor suspend functions are main-safe. For blocking work (Room without suspend, file IO, heavy parsing) switch dispatchers yourself:

```kotlin
queryFn {
    withContext(Dispatchers.IO) { repository.loadBlocking() }
}
```

## Sharing a query across composables

Every `rememberQuery` with the same key shares the same `QueryData`, so state and cache are shared automatically. Two composables can call it independently and stay in sync:

```kotlin
@Composable
fun Header() {
    val todos = LocalQueryClient.current.rememberQuery<List<Todo>> {
        queryKey = listOf("todos"); queryFn { api.getTodos() }
    }
    Text("${todos.data?.size ?: 0} todos")
}

@Composable
fun Body() {
    val todos = LocalQueryClient.current.rememberQuery<List<Todo>> {
        queryKey = listOf("todos"); queryFn { api.getTodos() }
    }
    // ...
}
```

To avoid repeating the config, wrap it:

```kotlin
@Composable
fun QueryClient.useTodos() = rememberQuery<List<Todo>> {
    queryKey = listOf("todos")
    queryFn { api.getTodos() }
}
```

> Always use the **same type `T`** for the same key. The cache stores values untyped and casts on read, so mixing types for one key causes a `ClassCastException`.

Next: [Mutations](mutations.md)
