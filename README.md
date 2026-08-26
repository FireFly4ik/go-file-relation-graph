# Go File Relation Graph

GoLand plugin that visualizes how Go files are connected through direct calls, interface dispatch,
and callbacks. It provides a live graph for open files and snapshot parent graphs for a function or
method selected in the editor.

## Current behavior

### Open Files

- Nodes are open, non-generated Go files.
- Solid relations are direct function or concrete-method calls.
- Dashed purple relations are calls dispatched through an interface.
- Passing a function or method as a value also creates a relation.
- Opposite relations between the same two files share one line with arrows at both ends.
- Files on the same level with identical outgoing relations form a visual group: incoming arrows
  still reach each file, while repeated outgoing arrows share one collector and one set of labels.
- Dragging a grouped file or the group frame moves the complete group while keeping its internal layout locked.
- Files called earlier by the same parent are placed further left on their level.
- Click a file tile to open the file.
- Click a callable label to open its implementation.
- Shift-click a callable label to open the call site, or choose one when there are several.
- Right-click an interface-dispatched label to open the parent interface used by the call.
- Drag a tile to reposition it and drag the canvas to pan. Use a mouse wheel or a macOS trackpad pinch to zoom.
- Use View Options to control auto-refresh, include test files, or show files without relations.

### Parent Graph

Place the caret inside a named Go function or method and choose **Build Parent Graph** from the
editor context menu. A new closable `Parents: …` tab is added next to the permanent
`Open Files` tab.

- The search walks possible callers breadth-first inside the nearest `go.mod`.
- Direct calls, calls through interfaces, and functions or methods passed as call arguments are supported.
- SDK, module-cache, vendor, generated, and other-module files are excluded. Tests can be enabled per tab.
- Parent snapshots use a breadth-first hierarchy and default to 50 parent files and 10 visible file
  levels including the selected function. Change `Max parent files` in a parent tab when a larger
  snapshot is needed.
- Wide parent levels are split into consecutive rows without mixing files from different breadth-first levels.
- Horizontal ordering uses every relation that points to a lower breadth-first level, including relations that
  cycle breaking previously classified as reverse edges, and gives repeated relations more alignment weight.
- Reaching a limit adds a `More parents not shown` tile and shows a notification.
- Cycles are not drawn in the current version.
- Click a file tile to open its only participating function, or choose a function when the file contains several.
- Parent tabs are snapshots. Use **Refresh Parent Graph** to rebuild one; tabs are not restored after restart.
- Parent snapshots use the same identical-outgoing-relation grouping as the open-files graph.

For example, passing a method as a callback produces a direct visible parent relation without a
tile for the registration helper:

```go
func Register(callback func(http.ResponseWriter, *http.Request)) {
    router.HandleFunc("/booking", callback)
}

func Configure(handler *Handler) {
    Register(handler.Create)
}
```

The parent graph contains `configure.go / Configure()` → `handler.go / (*Handler).Create()` with a
callback-argument relation.

## Development

Requirements:

- JDK 21
- IntelliJ IDEA with Plugin DevKit

Run a development GoLand instance:

```shell
./gradlew runIde
```

Build an installable archive:

```shell
./gradlew test buildPlugin verifyPlugin
```

The resulting ZIP is written to `build/distributions/`. In GoLand, open
**Settings | Plugins**, use the gear menu, choose **Install Plugin from Disk**,
and select the ZIP without unpacking it.
