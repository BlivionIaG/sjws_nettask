# Request filters

Filters sit between protocol decoding and the terminal `Handler`. That terminal is
`StaticFileHandler` unless a caller installs another one (a router). HTTP/1.1 and HTTP/3 use one
chain (a future HTTP/2 listener should too). With nothing registered, and the terminal still the
static file handler, that chain is not built and not called: HTTP/1.1 calls
`StaticFileHandler.handle` directly, and HTTP/3 keeps the `files::handle` method reference it
already used.

Register before start. `SjwsServer.start(config, filters)` compiles the registry once and shares
it. `filterChain()` is null when the registry was null or empty. `handler()` stays the
`StaticFileHandler` either way (the file cache lives there).

## Write a filter

```java
public final class AccessLog implements RequestFilter {
    public static RequestFilter create(MiddlewareSpec spec) {
        return new AccessLog();
    }

    @Override
    public Response filter(Request request, Chain next) {
        long start = System.nanoTime();
        Response response = next.proceed(request); // at most once, same thread
        long nanos = System.nanoTime() - start;
        // status, headers and nanos are the handler's result, before the socket write
        return response;
    }
}
```

`Request` is an immutable record. `Response` is a record too; its `headers()` are a mutable
map, and the chain does not copy them. Pass `request.withMethod` / `request.withTarget` into
`proceed` to change what the rest of the chain sees; those copies keep the peer address,
`secure`, and headers. Return a new `Response` to change the status or body, or mutate
`response.headers()` in place. Skip `proceed` to short-circuit; later filters and the terminal
handler do not run.

`request.peer()` is the client `InetSocketAddress` (one per HTTP/1 connection, one per HTTP/3
stream), or null if the transport could not read it. `request.secure()` is false on plain HTTP/1
and true on HTTP/3. `request.headers().fields()` is the raw field map (lower-case name to values,
first-seen order, pseudo-headers excluded). HTTP/1 stores the parser's map by reference: that map
is built while parsing either way, and `RequestHeaders.of` does not copy it. `reset()` drops the
parser's reference and does not clear the map, so a filter may retain the request. HTTP/3 copies
every regular header only when a filter chain or a non-static terminal is installed (decided once
at startup). With neither, it reads the six typed fields and leaves `fields()` empty, so a static
request allocates no header map. When it does copy, that happens before the HEADERS frame is
released, because the handler runs later on another thread; the copy does not follow later changes
to the frame. `values(name)` does not allocate when the name is absent. `all()` flattens and
allocates; the static file handler does not call it. `:authority` wins over `host` for
`headers().host()`, and a copied `host` field stays in the map.

A filter must be safe for concurrent requests. Call `proceed` synchronously, on the request
thread. The chain rejects a second call on that thread (`IllegalStateException`, and the rest of
the chain does not run again). That failure becomes the normal HTML 500 unless the filter catches
it. The check is thread-local, so it does not treat another request's `proceed` as a duplicate,
and it is not created when no filters are registered. The time around `proceed` stops when the
downstream handler has *built* the response. The transport writes the body after the chain
returns, so this is not time-to-last-byte. `Date`, `Server`, `Connection`, `Content-Length` and
`Alt-Svc` are added by the writers after the chain. `X-Content-Type-Options` and
`Content-Security-Policy` are put on the handler's response, so a filter sees them and can change
them. Protocol failures that never reach the file handler (HTTP/1 parse errors and 408, HTTP/3
431 / bad target / unknown method) do not enter the chain either. A runtime exception or a null
return, from a filter or from the terminal handler, becomes the normal HTML 500
(`StaticFileHandler.error`); outer filters see that 500 as the return value of `proceed`, and the
connection stays open. `Error` (including `AssertionError`) is not caught.

## Register

Programmatic, outermost first when the order is equal (lower order runs first on the way in):

```java
var filters = RequestFilters.create()
    .add((request, next) -> next.proceed(request))
    .add(100, "/api/*", (request, next) -> proxy(request));
SjwsServer.start(config, filters);
```

`RoutePattern` is `*` (or omitted) for every request, `/exact`, or `/prefix/*` (the path equals
`/prefix` or starts with `/prefix/`). The query and fragment are ignored. The path is not
percent-decoded.

## Configuration hook

This server does not read a config file. The configuration workstream parses a `middleware` list
into `MiddlewareSpec` and passes it here after binding each name:

```java
var filters = RequestFilters.create()
    .factory("access-log", AccessLog::create)
    .factory("reverse-proxy", ReverseProxy::create)
    .addAll(specs); // List<MiddlewareSpec>, order field re-sorts, list order breaks ties
SjwsServer.start(config, filters);
```

Expected section (global entries and per-route entries in one list; the same name may repeat):

```yaml
middleware:
  - name: access-log          # required, matches factory(name)
    order: 0                  # optional, default 0; lower is further out
    options:                  # optional map of strings, interpreted by that filter
      format: combined
  - name: reverse-proxy
    order: 100
    route: /api/*             # optional; omitted, blank or "*" = every request
    options:
      upstream: http://127.0.0.1:9000
      preserve-host: "true"
```

| Field | Type | Required | Meaning |
|---|---|---|---|
| `name` | string | yes | factory id (`RequestFilters.factory`) |
| `order` | int | no (0) | lower runs first on the way in, last on the way out |
| `route` | string | no (all) | `*`, `/exact`, `/prefix/*` |
| `options` | map of strings | no | filter-defined; numbers and booleans are strings (`"5"`, `"true"`) |

`MiddlewareSpec.of(name, order, route, options)` is that entry. Factories are resolved when the
chain is built, so they can be bound before or after `addAll`. An unknown name, or a factory that
returns null, fails the build. Building snapshots the list: later `add` calls do not change a
chain already started.

## Terminal handler

Filters wrap whatever `Handler` is terminal. The routing workstream installs a router only when
routing is enabled, and leaves the static handler in place otherwise:

```java
SjwsServer.start(config, h3Factory, globalFilters, files -> new Router(routes, files));
```

A null selector, a null return, or returning `files` itself keeps the direct
`StaticFileHandler.handle` call when `globalFilters` is also null or empty. When filters are
registered they wrap the router (or the static handler). `filterChain()` is still null when no
filters were registered, even if a router is installed: HTTP/1.1 then calls the router through
`Handler.handle`, and HTTP/3 uses `FilterChain.dispatch(files, router, null)`, which is
`router::handle`.

Per-route filters do not need a router feature. Wrap that route's target handler. An empty
registry returns the same instance, so a route with no filters allocates nothing:

```java
Handler proxied = RequestFilters.create()
    .add(0, (request, next) -> next.proceed(request))
    .wrapping(proxyHandler);
```

## Request body

This record has no body. Do not add a buffered `byte[]`: a proxy that relays a body needs a
streaming read, and the static file handler must keep ignoring bodies.

Recommended later change (additive, so the static path stays as it is), before a proxy phase that
forwards bodies:

* `Request.body()` returns a pull-style `RequestBody`. The default is empty, with a known length
  of 0. HTTP/1 produces it from `Content-Length` or chunked decoding; HTTP/3 produces it from
  DATA frames. The static handler does not read it. If the terminal handler does not consume it,
  the transport drains it or closes the connection. Unknown length is allowed (chunked, or HTTP/3
  until the stream ends).
* Response side: `Body` gains a `Stream` variant, `length` or `-1` when the length is unknown,
  plus a pull source. HTTP/1 writes chunked or close-delimited. HTTP/3 writes DATA frames. A
  source error aborts the connection or stream and must not finish as a short 200.

Proxy phase 1 can stay bodyless (methods that carry a body are rejected) until that core change
lands. The fields above (`peer`, `secure`, `headers().fields()`) are already on `Request`, and
`withMethod` / `withTarget` keep them, so a streaming body can be added beside them later.

## Custom transports

A custom `Transport.Factory` that serves requests must override
`create(config, handler, chain, terminal)`. `SjwsServer` calls that form. The three-argument
default exists so a factory that already overrides it still receives the chain; the four-argument
default forwards to it and drops `terminal`. Dispatch with
`FilterChain.dispatch(handler, terminal, chain)`: a non-null chain is `chain::handle` (the chain
already closes over its terminal); a null chain and a null or static-handler terminal is
`handler::handle`; any other terminal is `terminal::handle`. HTTP/3's factory does this. A future
HTTP/2 listener should do the same, so the empty path stays a direct call on the concrete handler.
