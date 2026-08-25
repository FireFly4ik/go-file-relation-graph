# Go File Relation Graph

GoLand plugin that visualizes calls and interface implementations between Go files currently open in the editor.

## Current behavior

- Nodes are open, non-generated Go files.
- Solid relations are direct function or concrete-method calls.
- Dashed purple relations are calls dispatched through an interface.
- Passing a function or method as a value also creates a relation.
- Opposite relations between the same two files share one line with arrows at both ends.
- Files called earlier by the same parent are placed further left on their level.
- Click a file tile to open the file.
- Click a callable label to open its implementation.
- Shift-click a callable label to open the call site, or choose one when there are several.
- Right-click an interface-dispatched label to open the parent interface used by the call.
- Drag a tile to reposition it and drag the canvas to pan. Use a mouse wheel or a macOS trackpad pinch to zoom.
- Use View Options to control auto-refresh, include test files, or show files without relations.

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
