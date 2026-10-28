# Getting started

This page takes you from zero to a working query and mutation.

## 1. Requirements

- Kotlin with Jetpack Compose
- `androidx.lifecycle:lifecycle-runtime-compose` (used for `LifecycleEventEffect`)
- `androidx.lifecycle:lifecycle-viewmodel-compose` (to host the client in a ViewModel)
- `kotlinx-coroutines`

The library lives in the package `com.github.devlotfi.composequery`:

```kotlin
import com.github.devlotfi.composequery.QueryCache
import com.github.devlotfi.composequery.QueryClient
import com.github.devlotfi.composequery.rememberQuery
import com.github.devlotfi.composequery.rememberMutation
```

## 2. Create the client in a ViewModel

The `QueryClient` needs two things:

| Parameter | Purpose |
|---|---|
| `scope: CoroutineScope` | Where all fetches and mutations run. Use `viewModelScope` so work survives rotation and is cancelled when the ViewModel is cleared. |
| `cache: QueryCache` | Where the last successful result of each key is stored. |

```kotlin
class AppViewModel : ViewModel() {
    val queryClient = QueryClient(
        scope = viewModelScope,
        cache = QueryCache(),
    )
}
```

### With Hilt

```kotlin
@HiltViewModel
class AppViewModel @Inject constructor() : ViewModel() {
    val queryClient = QueryClient(viewModelScope, QueryCache())
}
```

### With Koin

```kotlin
val appModule = module {
    viewModel { AppViewModel() }
}
```

> **Tip:** Scope the ViewModel to the Activity (or navigation graph) if you want one shared client and cache for the whole app. Scope it to a single screen if you want the cache to die with that screen.

## 3. Provide the client to your composables

Use a `CompositionLocal` so every screen can reach the client without passing it down manually.

```kotlin
val LocalQueryClient = staticCompositionLocalOf<QueryClient> {
    error("No QueryClient provided. Wrap your content in CompositionLocalProvider.")
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

Or with `viewModel()` inside Compose:

```kotlin
setContent {
    val appViewModel: AppViewModel = viewModel()
    CompositionLocalProvider(LocalQueryClient provides appViewModel.queryClient) {
        AppNavHost()
    }
}
```

## 4. Your first query

```kotlin
data class Todo(val id: Int, val title: String, val done: Boolean)

@Composable
fun TodosScreen() {
    val queryClient = LocalQueryClient.current

    val todos = queryClient.rememberQuery<List<Todo>> {
        queryKey = listOf("todos")
        queryFn { api.getTodos() }
    }

    when {
        todos.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            CircularProgressIndicator()
        }
        todos.data == null -> Column {
            Text("Something went wrong: ${todos.error?.message}")
            Button(onClick = todos.refetch) { Text("Retry") }
        }
        else -> LazyColumn {
            items(todos.data!!, key = { it.id }) { TodoRow(it) }
        }
    }
}
```

What happens:

1. On first composition, a fetch starts and `loading = true`.
2. When `queryFn` returns, `data` is set, written to the cache, and `loading = false`.
3. If you leave and return, cached data shows **immediately** (`loading = false`) while a background refetch runs (`fetching = true`).
4. If the app goes to the background and comes back, the query refetches automatically.

> **Always specify the type argument** (`rememberQuery<List<Todo>>`) so Kotlin can infer `T`.

## 5. Your first mutation

```kotlin
@Composable
fun AddTodoButton() {
    val queryClient = LocalQueryClient.current

    val addTodo = queryClient.rememberMutation<Todo, String> {
        mutateKey = listOf("addTodo")
        mutateFn { title -> api.createTodo(title) }
        onSuccess {
            queryClient.refetchQueries(listOf("todos"))
        }
        onError { e ->
            Log.e("Todos", "Failed to add todo", e)
        }
    }

    Button(
        onClick = { addTodo.mutate("Buy milk") },
        enabled = !addTodo.loading,
    ) {
        Text(if (addTodo.loading) "Adding..." else "Add todo")
    }

    addTodo.error?.let { Text("Error: ${it.message}", color = MaterialTheme.colorScheme.error) }
}
```

The two type parameters of `rememberMutation<T, S>` are:

- `T`: the **result** type returned by `mutateFn`
- `S`: the **parameter** type passed to `mutate(params)`

After the mutation succeeds, `queryClient.refetchQueries(listOf("todos"))` makes the list refresh.

## Next

- [Queries in depth](queries.md)
- [Mutations in depth](mutations.md)
