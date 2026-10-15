<img src="https://raw.githubusercontent.com/devlotfi/compose-query/master/github-assets/github-banner.png">

# 📜 compose-query

A lightweight data-fetching and caching library for **Jetpack Compose**, inspired by [TanStack Query](https://tanstack.com/query). 
Fetch async data, share it across screens through a keyed cache, and keep it fresh with refetching, 
invalidation and mutations, all with a small, composable API.

```kotlin
val users = rememberQuery(
    queryFn = { api.getUsers() },
    queryKey = listOf("users")
)
```

## Features

- **Keyed query cache**: every query is identified by a `queryKey`; composables using the same key share the same data.
- **Stale-while-revalidate**: cached data is shown instantly while a fresh fetch runs in the background.
- **Granular state flags**: `loading`, `fetching` and `interactiveFetching` let you tell first load, background refresh and pull-to-refresh apart.
- **Refetch on resume**: queries automatically refresh when the screen returns to the foreground (configurable).
- **Request cancellation & race protection**: a new fetch cancels the in-flight one, and stale requests can't overwrite state.
- **Query invalidation**: refetch or reset queries by exact key or by key prefix.
- **Manual cache access**: read and write the cache directly, useful for optimistic updates.
- **Mutations**: `rememberMutation` with `onSuccess` / `onError` callbacks.
- **Survives configuration changes**: the cache lives in a `ViewModel`.
- **Thread-safe cache**: all cache operations are synchronized; observers are notified on `Dispatchers.Main.immediate`.

# 📌 Contents

- [Installation](#installation)
- [Docs](#docs)

# Installation

ComposeQuery is distributed through [JitPack](https://jitpack.io/#devlotfi/compose-query).
 
[![](https://jitpack.io/v/devlotfi/compose-query.svg)](https://jitpack.io/#devlotfi/compose-query)
 
Add the JitPack repository at the end of the repositories block in your root `settings.gradle.kts`:
 
## Setup
 
Wrap your UI in `QueryProvider` once, near the root of your app. It creates the shared cache and exposes it through a `CompositionLocal`.
 
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QueryProvider {
                AppNavigation()
            }
        }
    }
}
```
 
`QueryProvider` takes an optional `viewModel: QueryProviderViewModel` parameter (defaults to `viewModel()`), which is handy for tests.
 
---

# Docs

## Queries
 
### Basic usage
 
```kotlin
@Composable
fun UserListScreen() {
    val query = rememberQuery(
        queryFn = { api.getUsers() },      // suspend () -> List<User>?
        queryKey = listOf("users")
    )
 
    when {
        query.loading -> CircularProgressIndicator()
        query.error != null -> Text("Error: ${query.error?.message}")
        else -> LazyColumn {
            items(query.data.orEmpty()) { user -> Text(user.name) }
        }
    }
}
```
 
### Return values
 
`rememberQuery` returns a `RememberQueryReturn<T>`:
 
| Property | Type | Description |
|---|---|---|
| `loading` | `Boolean` | `true` while fetching **and** there is no data yet (first load). |
| `fetching` | `Boolean` | `true` whenever a fetch is running, including background refreshes. |
| `interactiveFetching` | `Boolean` | `true` while a fetch started by `fetchInteractively()` is running. Ideal for pull-to-refresh indicators. |
| `error` | `Exception?` | The last error, or `null`. A `null` result from `queryFn` produces `Exception("Empty")`. |
| `data` | `T?` | The latest data (from cache or network). Previous data is kept while refetching. |
| `refetch` | `() -> Unit` | Refetch silently (cached data stays visible). |
| `fetchInteractively` | `() -> Unit` | Refetch and set `interactiveFetching = true`. |
| `reset` | `() -> Unit` | Clear `data` and fetch from scratch (shows `loading` again). |
 
### Parameters
 
| Parameter | Type | Default | Description |
|---|---|---|---|
| `queryFn` | `suspend () -> T?` | required | Function that loads the data. |
| `queryKey` | `List<Any?>` | required | Cache key. Include every variable the query depends on. |
| `refetchOnResume` | `Boolean` | `true` | Refetch when the lifecycle reaches `ON_RESUME` (the first resume is skipped, since the initial fetch already ran). |
 
### Query keys
 
Keys are lists, compared by equality. Put the parameters your query depends on into the key so each combination gets its own cache entry:
 
```kotlin
@Composable
fun UserDetails(userId: Int) {
    val query = rememberQuery(
        queryFn = { api.getUser(userId) },
        queryKey = listOf("users", userId)
    )
    // ...
}
```
 
When `queryKey` changes, the query unsubscribes from the old entry, subscribes to the new one and fetches again, showing any data already cached under the new key.
 
### Pull to refresh
 
Use `fetchInteractively` with `interactiveFetching` so a background refresh doesn't trigger the pull-to-refresh spinner, but a user-initiated one does:
 
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListScreen() {
    val query = rememberQuery(
        queryFn = { api.getUsers() },
        queryKey = listOf("users")
    )
 
    PullToRefreshBox(
        isRefreshing = query.interactiveFetching,
        onRefresh = query.fetchInteractively
    ) {
        LazyColumn {
            items(query.data.orEmpty()) { Text(it.name) }
        }
    }
}
```
 
### Showing a subtle "refreshing" indicator
 
```kotlin
Column {
    if (query.fetching && !query.loading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    // content...
}
```
 
### Disabling refetch on resume
 
```kotlin
val query = rememberQuery(
    queryFn = { api.getSettings() },
    queryKey = listOf("settings"),
    refetchOnResume = false
)
```
 
### Shared data between screens
 
Two composables using the same key share one cache entry. Once one fetches, the other is updated automatically:
 
```kotlin
@Composable
fun Header() {
    val user = rememberQuery(queryFn = { api.getMe() }, queryKey = listOf("me"))
    Text(user.data?.name ?: "…")
}
 
@Composable
fun ProfileScreen() {
    val user = rememberQuery(queryFn = { api.getMe() }, queryKey = listOf("me"))
    // Shows cached data immediately if Header already loaded it
}
```
 
---
 
## Mutations
 
`rememberMutation` wraps a write operation (POST / PUT / DELETE…).
 
```kotlin
@Composable
fun AddUserButton() {
    val queryClient = LocalQuery.current
 
    val mutation = rememberMutation<User, NewUser>(
        mutateFn = { params -> api.createUser(params) },
        onSuccess = {
            // refresh the list after a successful write
            queryClient.refetchQueries(listOf("users"), true)
        },
        onError = { e -> Log.e("AddUser", "Failed", e) }
    )
 
    Button(
        enabled = !mutation.loading,
        onClick = { mutation.mutate(NewUser(name = "Ada")) }
    ) {
        Text(if (mutation.loading) "Saving…" else "Add user")
    }
 
    mutation.error?.let { Text("Error: ${it.message}") }
}
```
 
### Return values
 
| Property | Type | Description |
|---|---|---|
| `loading` | `Boolean` | `true` while the mutation is running. |
| `error` | `Exception?` | The error from the last failed run. |
| `data` | `T?` | The result of the last successful run. |
| `mutate` | `(S) -> Unit` | Runs `mutateFn` with the given params. |
 
### Parameters
 
| Parameter | Type | Description |
|---|---|---|
| `mutateFn` | `suspend (S) -> T?` | The write operation. Receives the params passed to `mutate`. |
| `onSuccess` | `(suspend () -> Unit)?` | Called after `mutateFn` succeeds. |
| `onError` | `(suspend (Exception) -> Unit)?` | Called when `mutateFn` throws. |
 
---
 
## Invalidating & managing the cache
 
Access the cache client anywhere inside `QueryProvider` with `LocalQuery.current`:
 
```kotlin
val queryClient = LocalQuery.current
```
 
| Function | Description |
|---|---|
| `refetchQueries(queryKey, exact)` | Tell active queries to refetch. Cached data stays visible while refetching. |
| `resetQueries(queryKey, exact)` | Clear cached data and refetch from scratch (observers show `loading` again). |
| `setCache(queryKey, value)` | Write a value into the cache and update every observer of that key. |
| `getCache(queryKey)` | Read the current cached value (or `null`). |
| `subscribe` / `unsubscribe` | Low-level observer registration (used internally by `rememberQuery`). |
 
### Exact vs. prefix matching
 
With `exact = true` only the entry whose key equals `queryKey` is affected. With `exact = false`, every key that **starts with** `queryKey` is affected:
 
```kotlin
// Cache contains: ["users"], ["users", 1], ["users", 2], ["posts"]
 
queryClient.refetchQueries(listOf("users"), true)   // only ["users"]
queryClient.refetchQueries(listOf("users"), false)  // ["users"], ["users", 1], ["users", 2]
```
 
### Optimistic updates
 
Update the cache immediately, then reconcile with the server:
 
```kotlin
val queryClient = LocalQuery.current
 
val toggleDone = rememberMutation<Todo, Todo>(
    mutateFn = { todo ->
        // optimistic update
        @Suppress("UNCHECKED_CAST")
        val current = queryClient.getCache(listOf("todos")) as? List<Todo> ?: emptyList()
        queryClient.setCache(
            listOf("todos"),
            current.map { if (it.id == todo.id) todo.copy(done = !todo.done) else it }
        )
        api.toggleTodo(todo.id)
    },
    onSuccess = { queryClient.refetchQueries(listOf("todos"), true) },
    onError = { queryClient.refetchQueries(listOf("todos"), true) } // roll back
)
```
 
### Resetting on logout
 
```kotlin
Button(onClick = {
    auth.logout()
    queryClient.resetQueries(emptyList(), false) // empty prefix matches every query
}) { Text("Log out") }
```
 
---
 
## How it works
 
1. `QueryProvider` creates a `QueryProviderViewModel` that holds a `HashMap<List<Any?>, CacheEntry>`. Each entry stores the cached value and a list of observers.
2. `rememberQuery` subscribes to its key, seeds its state from the cache, and fetches.
3. When a fetch succeeds the result is written with `setCache`, which notifies all observers of that key with `DataChanged`, so every screen using the key updates.
4. `refetchQueries` / `resetQueries` notify observers with `Refetch` / `Reset`; each active `rememberQuery` reacts by running its own fetch.
5. Starting a new fetch cancels the previous job. A request counter ensures only the latest request clears the `loading` / `fetching` flags.
---
 
## Good to know
 
- **Key stability**: query keys are compared with `equals`. Use primitives, strings, enums or data classes, not objects without value equality.
- **`queryFn` identity**: `rememberQuery` re-subscribes and refetches when `queryKey` **or** `queryFn` changes. A lambda that captures unstable values can be recreated on recomposition and cause repeated fetches. Capture stable values, or wrap the function with `remember`.
- **Empty results**: if `queryFn` returns `null`, `error` is set to `Exception("Empty")`.
- **Mutation state**: `error` and `data` in `rememberMutation` are overwritten by the next run and are not cleared when a new `mutate` call starts.
- **Invalidation arguments**: when calling through `LocalQuery.current`, pass `exact` explicitly (`refetchQueries(key, true)`).
- **Generics**: cached values are stored as `Any?` and cast to `T` on read. Use consistent types per key.
---
