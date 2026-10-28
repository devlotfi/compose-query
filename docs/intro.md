# compose-query

Server-state management for Jetpack Compose, inspired by [TanStack Query](https://tanstack.com/query).

`compose-query` gives you declarative data fetching with caching, loading/error states, refetching, and mutations, without hand-writing `MutableStateFlow`s and `launch` blocks in every ViewModel.

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
        todos.error != null && todos.data == null -> ErrorView(todos.error!!, onRetry = todos.refetch)
        else -> TodoList(todos.data.orEmpty())
    }
}
```

## Features

| Feature | Description |
|---|---|
| Declarative queries | `rememberQuery { }` returns `loading`, `fetching`, `error`, `data` as Compose state |
| In-memory cache | Cached data is shown instantly while a background refetch runs |
| Refetch on resume | Automatically refetches when the app returns to the foreground (configurable) |
| Interactive fetching | A separate `interactiveFetching` flag, ideal for pull-to-refresh indicators |
| Mutations | `rememberMutation { }` with `onSuccess` / `onError` callbacks and double-submit protection |
| Invalidation | `refetchQueries` / `resetQueries` by exact key or by key prefix |
| Request de-duplication | Newer requests cancel older ones; stale results never overwrite fresh state |
| Survives configuration changes | The `QueryClient` lives in a ViewModel and runs on `viewModelScope` |
| Tiny API surface | Two composables, one client, one cache |

## Documentation

Read the pages in order the first time, then use them as reference.

1. [Getting started](getting-started.md): setup, ViewModel, providing the client
2. [Queries](queries.md): `rememberQuery`, states, keys, parameters, refetching
3. [Mutations](mutations.md): `rememberMutation`, callbacks, invalidating queries
4. [QueryClient and cache](query-client-and-cache.md): invalidation, prefix matching, manual access, lifecycle
5. [Recipes](recipes.md): CRUD list, search, pull-to-refresh, dependent queries, optimistic updates, and more
6. [API reference](api-reference.md): every class, property, and function
7. [Caveats and limitations](caveats-and-limitations.md): behaviors worth knowing before you ship

## Mental model

```
┌─────────────────────────── ViewModel ───────────────────────────┐
│  QueryClient(scope = viewModelScope, cache = QueryCache())      │
│    ├─ queryDataStore    key ──► QueryData<T>   (state + job)    │
│    ├─ mutationDataStore key ──► MutationData<T,S>               │
│    └─ cache.store       key ──► last successful data            │
└─────────────────────────────────────────────────────────────────┘
                 ▲                              │
   rememberQuery / rememberMutation             │ Compose state
                 │                              ▼
          ┌───────────── Composables ─────────────┐
          │  loading / fetching / error / data    │
          └───────────────────────────────────────┘
```

- A **query key** (`List<Any?>`) identifies a piece of server data. Same key means same `QueryData` and same cached value.
- A **query** is read-only: `queryFn` returns data and the library tracks its state.
- A **mutation** is a write: you call `mutate(params)`, then usually invalidate related queries.
- Work runs on the client's `scope` (normally `viewModelScope`), **not** on the composition. Leaving a screen does not cancel an in-flight request.
