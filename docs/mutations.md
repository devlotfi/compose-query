# Mutations

A mutation performs a **write**: create, update, delete, login, upload, and so on. Unlike queries, mutations never run automatically. You trigger them with `mutate(params)`.

```kotlin
val mutation = queryClient.rememberMutation<T, S> {
    mutateKey = listOf(...)
    mutateFn { params: S -> ... }   // returns T?
    onSuccess { ... }               // optional
    onError { e -> ... }            // optional
}

mutation.mutate(params)
```

Type parameters:

- `T`: result type of `mutateFn`
- `S`: parameter type passed to `mutate`

## Configuration

| Option | Type | Default | Description |
|---|---|---|---|
| `mutateKey` | `List<Any?>` | **required** | Identifies this mutation in the client. Composables using the same key share state. |
| `mutateFn { params -> }` | `suspend (S) -> T?` | **required** | Performs the write. Returning `null` is treated as an error. |
| `onSuccess { }` | `suspend () -> Unit` | no-op | Called after a successful run. Receives **no arguments**. |
| `onError { e -> }` | `suspend (Exception) -> Unit` | no-op | Called when the run fails. |

## Return value

`RememberMutationReturn<T, S>`:

| Property | Type | Meaning |
|---|---|---|
| `loading` | `Boolean` | `true` while the mutation is running. |
| `error` | `Exception?` | The last error, cleared when a new run starts. |
| `data` | `T?` | The result of the **last successful** run. Not cleared when a new run starts. |
| `mutate` | `(S) -> Unit` | Starts the mutation. |

## Behavior

1. `mutate(params)` is **ignored while a previous run is still active**. This protects against double taps without any extra code.
2. On start: `error = null`, `loading = true`.
3. `mutateFn(params)` runs. If it returns a non-null value, `data` is set and `onSuccess` is invoked.
4. If it returns `null`, `error = Exception("Empty")`. `onError` is **not** called for this case.
5. If it throws, `error` is set and `onError(e)` is invoked.
6. `loading = false` in all cases.
7. Cancellation is rethrown and is not treated as an error.

> **Void endpoints:** return `Unit` (or `true`) from `mutateFn`, not `null`. A `null` result is treated as failure.
> ```kotlin
> mutateFn { id -> api.deleteTodo(id); Unit }
> ```

## Examples

### Create

```kotlin
@Composable
fun NewTodoForm() {
    val queryClient = LocalQueryClient.current
    var title by remember { mutableStateOf("") }

    val createTodo = queryClient.rememberMutation<Todo, String> {
        mutateKey = listOf("todos", "create")
        mutateFn { newTitle -> api.createTodo(newTitle) }
        onSuccess { queryClient.refetchQueries(listOf("todos"), exact = false) }
    }

    Column {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            isError = createTodo.error != null,
            supportingText = { createTodo.error?.message?.let { Text(it) } },
        )
        Button(
            onClick = { createTodo.mutate(title) },
            enabled = title.isNotBlank() && !createTodo.loading,
        ) {
            if (createTodo.loading) CircularProgressIndicator(Modifier.size(16.dp))
            else Text("Create")
        }
    }
}
```

### Update with several parameters

Mutations take a single parameter. Use a data class for more:

```kotlin
data class UpdateTodoParams(val id: Int, val title: String, val done: Boolean)

val updateTodo = queryClient.rememberMutation<Todo, UpdateTodoParams> {
    mutateKey = listOf("todos", "update")
    mutateFn { p -> api.updateTodo(p.id, p.title, p.done) }
    onSuccess { queryClient.refetchQueries(listOf("todos"), exact = false) }
}

Checkbox(
    checked = todo.done,
    onCheckedChange = { updateTodo.mutate(UpdateTodoParams(todo.id, todo.title, it)) },
)
```

### Delete

```kotlin
val deleteTodo = queryClient.rememberMutation<Unit, Int> {
    mutateKey = listOf("todos", "delete")
    mutateFn { id -> api.deleteTodo(id) }
    onSuccess { queryClient.refetchQueries(listOf("todos"), exact = false) }
}

IconButton(onClick = { deleteTodo.mutate(todo.id) }, enabled = !deleteTodo.loading) {
    Icon(Icons.Default.Delete, contentDescription = "Delete")
}
```

### No parameters

Use `Unit` as `S`:

```kotlin
val logout = queryClient.rememberMutation<Unit, Unit> {
    mutateKey = listOf("auth", "logout")
    mutateFn { api.logout() }
}

Button(onClick = { logout.mutate(Unit) }) { Text("Log out") }
```

### Login and navigate

```kotlin
val login = queryClient.rememberMutation<Session, Credentials> {
    mutateKey = listOf("auth", "login")
    mutateFn { creds -> api.login(creds.email, creds.password) }
    onSuccess { navController.navigate("home") { popUpTo("login") { inclusive = true } } }
    onError { e -> snackbarHostState.showSnackbar(e.message ?: "Login failed") }
}
```

`onError` and `onSuccess` are `suspend`, so you can call suspending UI functions like `showSnackbar` directly.

## Reacting to the result

`onSuccess` receives no arguments. To use the returned value there are two options.

**Option 1: do the work inside `mutateFn`** (best for guaranteed side effects, such as saving a token):

```kotlin
mutateFn { creds ->
    val session = api.login(creds.email, creds.password)
    tokenStore.save(session.token)
    session
}
```

**Option 2: observe `data` in the UI:**

```kotlin
LaunchedEffect(login.data) {
    login.data?.let { navController.navigate("home") }
}
```

> `data` keeps the last successful value. On a screen that can be revisited, either clear your own state after handling it or prefer `onSuccess`.

## Callback lifetime (important)

Mutations run on the client's scope (`viewModelScope`), so they **keep running** if the user leaves the screen. However, when the composable that called `rememberMutation` **leaves composition**, `onSuccess` and `onError` are set to `null` (via `DisposableEffect`). This avoids calling into destroyed UI such as a stale `NavController` or `SnackbarHostState`.

Consequence:

- The write **still completes** on the server after the user navigates away.
- `onSuccess` / `onError` **will not fire** if the composable is gone by then.

If something **must** happen after the write regardless of UI (cache invalidation, saving data), do it inside `mutateFn`:

```kotlin
mutateFn { params ->
    val result = api.createTodo(params)
    queryClient.refetchQueries(listOf("todos"), exact = false)   // always runs
    result
}
```

Keep UI-related work (navigation, snackbars) in `onSuccess`/`onError`.

## Mutation keys and sharing

The `mutateKey` identifies the `MutationData`. If two composables use the same key they share `loading`, `error`, and `data`. That's useful for a global "saving..." indicator:

```kotlin
// In the form
val save = queryClient.rememberMutation<Unit, Draft> {
    mutateKey = listOf("saveDraft")
    mutateFn { api.saveDraft(it) }
}

// In the top bar
val saving = queryClient.rememberMutation<Unit, Draft> {
    mutateKey = listOf("saveDraft")
    mutateFn { api.saveDraft(it) }
}
if (saving.loading) Text("Saving...")
```

Use **different keys** for independent mutations. If every row in a list has a delete button with the same key, tapping one blocks the others while it runs. Include the item id in the key for independent state:

```kotlin
mutateKey = listOf("todos", "delete", todo.id)
```

## Retrying after an error

`error` is cleared as soon as `mutate` is called again. A "Retry" button simply calls `mutate` with the same params:

```kotlin
createTodo.error?.let {
    Row {
        Text(it.message ?: "Failed")
        TextButton(onClick = { createTodo.mutate(title) }) { Text("Retry") }
    }
}
```

Next: [QueryClient and cache](query-client-and-cache.md)
