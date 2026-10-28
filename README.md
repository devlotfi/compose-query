<img src="https://raw.githubusercontent.com/devlotfi/compose-query/master/github-assets/github-banner.png">

# Compose Query

[![JitPack](https://jitpack.io/v/devlotfi/compose-query.svg)](https://jitpack.io/#devlotfi/compose-query)

Compose Query is a lightweight server-state management library for Jetpack Compose, inspired by [TanStack Query](https://tanstack.com/query). It gives you declarative data fetching with caching, loading and error states, background refetching, and mutations, without hand-writing `MutableStateFlow`s and `launch` blocks in every ViewModel.

A single `QueryClient` lives in a ViewModel and runs on `viewModelScope`, so requests survive configuration changes. A separate `QueryCache` stores the last successful result of each query, so screens open instantly with cached data while fresh data loads in the background.

## Features

- **Declarative queries**: `rememberQuery { }` exposes `loading`, `fetching`, `error` and `data` as Compose state
- **In-memory cache**: cached data is shown instantly while a background refetch runs
- **Query keys**: identify data with a `List<Any?>` such as `listOf("todos", id)`
- **Refetch on resume**: data refreshes automatically when the app returns to the foreground (configurable per query)
- **Interactive fetching**: a dedicated `interactiveFetching` flag, ideal for pull-to-refresh indicators
- **Mutations**: `rememberMutation { }` with `onSuccess` / `onError` callbacks
- **Double-submit protection**: `mutate` is ignored while a previous run is still active
- **Invalidation**: `refetchQueries` and `resetQueries`, by exact key or by key prefix
- **Request de-duplication**: a newer request cancels the previous one, and stale results never overwrite fresh state
- **Shared state**: composables using the same key share one query and one cache entry
- **ViewModel-based**: survives configuration changes, and cancels all work when the ViewModel is cleared
- **Manual cache access**: pre-fill the cache or update live queries for optimistic updates
- **Tiny API surface**: two composables, one client, one cache

## Installation

Compose Query is published on [JitPack](https://jitpack.io/#devlotfi/compose-query).

## Documentation

- [Docs](https://github.com/devlotfi/compose-query/blob/main/docs/intro.md)
- [llms.txt](https://devlotfi.github.io/compose-query/llms.txt)

## Quick start

### 1. Create the client in a ViewModel

```kotlin
class AppViewModel : ViewModel() {
    val queryClient = QueryClient(
        scope = viewModelScope,
        cache = QueryCache(),
    )
}
```

### 2. Provide it to your composables

```kotlin
val LocalQueryClient = staticCompositionLocalOf<QueryClient> {
    error("No QueryClient provided")
}

class MainActivity : ComponentActivity() {
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalQueryClient provides appViewModel.queryClient) {
                AppNavHost()
            }
        }
    }
}
```

### 3. Fetch data with a query

```kotlin
@Composable
fun TodosScreen() {
    val queryClient = LocalQueryClient.current

    val todos = queryClient.rememberQuery<List<Todo>> {
        queryKey = listOf("todos")
        queryFn { api.getTodos() }
    }

    when {
        todos.loading -> CircularProgressIndicator()
        todos.data == null -> Column {
            Text("Error: ${todos.error?.message}")
            Button(onClick = todos.refetch) { Text("Retry") }
        }
        else -> LazyColumn {
            items(todos.data!!, key = { it.id }) { TodoRow(it) }
        }
    }
}
```

| State | Meaning |
|---|---|
| `loading` | No data yet and a fetch is running (first load) |
| `fetching` | Any fetch is running, including background refetches |
| `interactiveFetching` | A user-initiated fetch is running (pull-to-refresh) |
| `error` | The last error, or `null` |
| `data` | The last successful result, kept while refetching |

### 4. Pull to refresh

```kotlin
PullToRefreshBox(
    isRefreshing = todos.interactiveFetching,
    onRefresh = todos.fetchInteractively,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(todos.data.orEmpty()) { TodoRow(it) }
    }
}
```

### 5. Parameterized queries

Put every input in the key. Each key has its own cache entry, and `queryFn` receives the key.

```kotlin
@Composable
fun TodoDetail(todoId: Int) {
    val todo = LocalQueryClient.current.rememberQuery<Todo> {
        queryKey = listOf("todos", todoId)
        queryFn { key -> api.getTodo(key[1] as Int) }
    }
    todo.data?.let { Text(it.title) } ?: CircularProgressIndicator()
}
```

### 6. Write data with a mutation

```kotlin
@Composable
fun AddTodoButton() {
    val queryClient = LocalQueryClient.current

    val addTodo = queryClient.rememberMutation<Todo, String> {
        mutateKey = listOf("todos", "create")
        mutateFn { title ->
            val todo = api.createTodo(title)
            queryClient.refetchQueries(listOf("todos"), exact = false)
            todo
        }
        onError { e -> Log.e("Todos", "Failed to add todo", e) }
    }

    Button(
        onClick = { addTodo.mutate("Buy milk") },
        enabled = !addTodo.loading,
    ) {
        Text(if (addTodo.loading) "Adding..." else "Add todo")
    }

    addTodo.error?.let { Text(it.message ?: "Error", color = MaterialTheme.colorScheme.error) }
}
```

### 7. Invalidate queries

```kotlin
// Only ["todos"]
queryClient.refetchQueries(listOf("todos"))

// Every key starting with "todos": ["todos"], ["todos", 5], ["todos", "page", 2], ...
queryClient.refetchQueries(listOf("todos"), exact = false)

// Clear cached data and show the loading state again
queryClient.resetQueries(listOf("profile"))
```

### 8. Optimistic update

```kotlin
val todosData = queryClient.getQueryData<List<Todo>>(listOf("todos")) {
    QueryData(queryClient, listOf("todos")) { api.getTodos() }
}

val previous = todosData.data
todosData.data = previous?.map { if (it.id == id) it.copy(done = true) else it }  // instant UI

try {
    api.updateTodo(id, done = true)
} catch (e: Exception) {
    todosData.data = previous  // rollback
} finally {
    queryClient.refetchQueries(listOf("todos"))
}
```