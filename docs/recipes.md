# Recipes

Practical patterns built on top of `rememberQuery`, `rememberMutation` and the `QueryClient`.

All examples assume:

```kotlin
data class Todo(val id: Int, val title: String, val done: Boolean)

val LocalQueryClient = staticCompositionLocalOf<QueryClient> { error("No QueryClient") }
```

## Full CRUD list

One screen that lists, creates, toggles and deletes, with a single invalidation point.

```kotlin
@Composable
fun TodosScreen() {
    val queryClient = LocalQueryClient.current

    val todos = queryClient.rememberQuery<List<Todo>> {
        queryKey = listOf("todos")
        queryFn { api.getTodos() }
    }

    val toggle = queryClient.rememberMutation<Todo, Todo> {
        mutateKey = listOf("todos", "toggle")
        mutateFn { todo ->
            val updated = api.updateTodo(todo.id, todo.title, !todo.done)
            queryClient.refetchQueries(listOf("todos"), exact = false)
            updated
        }
    }

    val delete = queryClient.rememberMutation<Unit, Int> {
        mutateKey = listOf("todos", "delete")
        mutateFn { id ->
            api.deleteTodo(id)
            queryClient.refetchQueries(listOf("todos"), exact = false)
        }
    }

    when {
        todos.loading -> CircularProgressIndicator()
        todos.data == null -> Button(onClick = todos.refetch) { Text("Retry") }
        else -> LazyColumn {
            items(todos.data!!, key = { it.id }) { todo ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(todo.done, onCheckedChange = { toggle.mutate(todo) })
                    Text(todo.title, Modifier.weight(1f))
                    IconButton(onClick = { delete.mutate(todo.id) }) {
                        Icon(Icons.Default.Delete, "Delete")
                    }
                }
            }
        }
    }
}
```

## Pull to refresh

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen() {
    val feed = LocalQueryClient.current.rememberQuery<List<Post>> {
        queryKey = listOf("feed")
        queryFn { api.getFeed() }
    }

    PullToRefreshBox(
        isRefreshing = feed.interactiveFetching,
        onRefresh = feed.fetchInteractively,
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(feed.data.orEmpty()) { PostCard(it) }
        }
    }
}
```

## Background refresh indicator

Show a thin bar for any fetch that is not the first load and not user-initiated:

```kotlin
if (feed.fetching && !feed.loading && !feed.interactiveFetching) {
    LinearProgressIndicator(Modifier.fillMaxWidth())
}
```

## Detail screen with instant preview from list

Pre-fill the cache when the user taps a list item. The detail screen opens with content already visible, then refreshes.

```kotlin
// List
TodoRow(
    todo = todo,
    onClick = {
        queryClient.cache.store[listOf("todos", todo.id)] = todo
        navController.navigate("todo/${todo.id}")
    },
)

// Detail
@Composable
fun TodoDetail(id: Int) {
    val todo = LocalQueryClient.current.rememberQuery<Todo> {
        queryKey = listOf("todos", id)
        queryFn { api.getTodo(id) }
    }
    todo.data?.let { Text(it.title) } ?: CircularProgressIndicator()
}
```

## Search with filters and debounce

Put every filter in the key. Debounce the text before it reaches the key so you don't fire a request per keystroke.

```kotlin
@Composable
fun SearchScreen() {
    val queryClient = LocalQueryClient.current
    var text by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }

    LaunchedEffect(text) {
        delay(300)
        debounced = text
    }

    val results = queryClient.rememberQuery<List<Item>> {
        queryKey = listOf("search", debounced)
        queryFn { key -> api.search(key[1] as String) }
    }

    Column {
        OutlinedTextField(text, { text = it }, label = { Text("Search") })
        if (results.fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn { items(results.data.orEmpty()) { ItemRow(it) } }
    }
}
```

Previously searched terms are cached, so going back to an earlier term is instant. Invalidate them all with `queryClient.refetchQueries(listOf("search"), exact = false)`.

> While the key changes, `data` for the new key is `null` until its fetch completes, so the list briefly empties. To keep the previous results visible, remember the last non-null data yourself:
> ```kotlin
> var last by remember { mutableStateOf<List<Item>>(emptyList()) }
> results.data?.let { last = it }
> LazyColumn { items(last) { ItemRow(it) } }
> ```

## Simple pagination (page number in key)

Each page is its own cache entry.

```kotlin
@Composable
fun PagedList() {
    val queryClient = LocalQueryClient.current
    var page by remember { mutableIntStateOf(1) }

    val result = queryClient.rememberQuery<Page<Todo>> {
        queryKey = listOf("todos", "page", page)
        queryFn { key -> api.getTodosPage(key[2] as Int) }
    }

    Column {
        result.data?.items?.forEach { TodoRow(it) }
        Row {
            TextButton(onClick = { page-- }, enabled = page > 1) { Text("Prev") }
            Text("Page $page")
            TextButton(onClick = { page++ }, enabled = result.data?.hasNext == true) { Text("Next") }
        }
    }
}
```

Because every page key starts with `["todos"]`, a mutation that calls `refetchQueries(listOf("todos"), exact = false)` refreshes all visited pages.

## Dependent queries

There's no `enabled` option. Express the dependency through composition: only call the second `rememberQuery` once you have what it needs.

```kotlin
@Composable
fun ProfileWithPosts() {
    val queryClient = LocalQueryClient.current

    val user = queryClient.rememberQuery<User> {
        queryKey = listOf("me")
        queryFn { api.me() }
    }

    when {
        user.loading -> CircularProgressIndicator()
        user.data == null -> ErrorView(user.error)
        else -> PostsSection(userId = user.data!!.id)   // second query lives here
    }
}

@Composable
private fun PostsSection(userId: Int) {
    val posts = LocalQueryClient.current.rememberQuery<List<Post>> {
        queryKey = listOf("user", userId, "posts")
        queryFn { key -> api.postsOf(key[1] as Int) }
    }
    // ...
}
```

## Parallel queries

Call `rememberQuery` several times in the same composable. They run concurrently.

```kotlin
val profile = queryClient.rememberQuery<Profile> { queryKey = listOf("profile"); queryFn { api.profile() } }
val stats = queryClient.rememberQuery<Stats> { queryKey = listOf("stats"); queryFn { api.stats() } }

val allLoading = profile.loading || stats.loading
val firstError = profile.error ?: stats.error
```

## Optimistic updates

Update the UI immediately, then reconcile with the server. If the call fails, roll back.

```kotlin
fun QueryClient.todosQueryData() = getQueryData<List<Todo>>(listOf("todos")) {
    QueryData(this, listOf("todos")) { api.getTodos() }
}

@Composable
fun rememberToggleTodo(): RememberMutationReturn<Todo, Todo> {
    val queryClient = LocalQueryClient.current
    return queryClient.rememberMutation {
        mutateKey = listOf("todos", "toggleOptimistic")
        mutateFn { todo ->
            val todosData = queryClient.todosQueryData()
            val previous = todosData.data

            // 1. optimistic change
            todosData.data = previous?.map {
                if (it.id == todo.id) it.copy(done = !it.done) else it
            }

            try {
                // 2. real call
                api.updateTodo(todo.id, todo.title, !todo.done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 3. rollback
                todosData.data = previous
                throw e
            } finally {
                // 4. reconcile with the server either way
                queryClient.refetchQueries(listOf("todos"))
            }
        }
    }
}
```

Doing the optimistic work inside `mutateFn` (rather than `onSuccess`) guarantees rollback and reconciliation happen even if the user leaves the screen. See [callback lifetime](mutations.md#callback-lifetime-important).

## Navigate or show a snackbar after a mutation

```kotlin
@Composable
fun CreateTodoScreen(navController: NavController, snackbar: SnackbarHostState) {
    val queryClient = LocalQueryClient.current

    val create = queryClient.rememberMutation<Todo, String> {
        mutateKey = listOf("todos", "create")
        mutateFn { title ->
            val todo = api.createTodo(title)
            queryClient.refetchQueries(listOf("todos"), exact = false)
            todo
        }
        onSuccess { navController.popBackStack() }
        onError { e -> snackbar.showSnackbar(e.message ?: "Could not create todo") }
    }
    // form...
}
```

Invalidation lives in `mutateFn` (always runs); navigation and snackbar live in the callbacks (only meaningful while the screen is alive).

## Static data that should not refetch

```kotlin
val countries = queryClient.rememberQuery<List<Country>> {
    queryKey = listOf("countries")
    refetchOnResume = false
    queryFn { api.countries() }
}
```

On re-entry the cached list shows instantly and a quiet background fetch still runs. See [Caveats](caveats-and-limitations.md) if you need to avoid that as well.

## Switching accounts

On logout or account switch, cached data must not leak to the next user. The simplest and safest approach is to make the ViewModel that owns the client short-lived:

```kotlin
// e.g. an Activity-scoped ViewModel created per signed-in session, or
// recreate the Activity / navigation graph owning the ViewModel on logout.
```

If you must keep the same client, `resetQueries` clears values but **immediately refetches** them, so make sure your `queryFn`s tolerate being called while signed out:

```kotlin
queryClient.resetQueries(emptyList(), exact = false)
```

## Reusable query hooks

Wrap queries in extension functions to keep keys and functions in one place:

```kotlin
object Keys {
    val todos = listOf("todos")
    fun todo(id: Int) = listOf("todos", id)
}

@Composable
fun QueryClient.useTodos() = rememberQuery<List<Todo>> {
    queryKey = Keys.todos
    queryFn { api.getTodos() }
}

@Composable
fun QueryClient.useTodo(id: Int) = rememberQuery<Todo> {
    queryKey = Keys.todo(id)
    queryFn { api.getTodo(id) }
}

// Usage
val todos = LocalQueryClient.current.useTodos()
```

Centralizing keys prevents typos that would silently break invalidation.

## Testing

`QueryClient` takes any `CoroutineScope`, so you can inject a test scope:

```kotlin
@Test
fun refetchQueries_updatesData() = runTest {
    val client = QueryClient(scope = this, cache = QueryCache())

    var calls = 0
    val data = client.getQueryData<Int>(listOf("counter")) {
        QueryData(client, listOf("counter")) { ++calls }
    }

    data.fetchQuery()
    advanceUntilIdle()
    assertEquals(1, data.data)

    client.refetchQueries(listOf("counter"))
    advanceUntilIdle()
    assertEquals(2, data.data)
}
```

For UI tests, provide a client backed by a fake `queryFn` through your `CompositionLocalProvider`.
