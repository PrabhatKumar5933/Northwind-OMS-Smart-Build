# Northwind OMS Smart Build

## Implementation
The smart-build orchestration is implemented in Java (`SmartBuild.java`). The existing application remains a Java/OSGi project and Maven/Tycho remains responsible for the actual build.

The Java tool:
- detects changed files from Git (or accepts `--files` for deterministic testing)
- reads OSGi `MANIFEST.MF` package/bundle dependencies
- reads `feature.xml` plugin/feature relationships
- resolves local package imports to exporting bundles
- calculates all downstream dependents
- performs topological sorting with cycle detection
- prints dependency paths, selected modules, skipped modules and numbered build order
- invokes Maven/Tycho for the selected reactor projects
- writes a Graphviz DOT dependency graph when `--dot` is used

## Prerequisites
- Java 17+
- Git
- Maven 3.x
- Network access to the Eclipse p2 repository used by the supplied Tycho build, when Maven dependencies are not cached

## Commands
From `osgi-workshop`:

```bash
./build.sh --all
./build.sh --changed
./build.sh --changed --files catalog/plugins/com.northwind.oms.core/src/.../SomeFile.java,orders/plugins/com.northwind.oms.gateway/src/.../SomeFile.java,payment/plugins/com.northwind.oms.payment/src/.../SomeFile.java
./build.sh --changed --no-build --dot --files catalog/plugins/com.northwind.oms.core/src/.../SomeFile.java,orders/plugins/com.northwind.oms.gateway/src/.../SomeFile.java,payment/plugins/com.northwind.oms.payment/src/.../SomeFile.java
./build.sh --graph    # print the complete dependency graph without running Maven
```

`--no-build` runs change/dependency analysis and logging without invoking Maven. This is useful for deterministic testing of the smart-build decision logic.

## CI workflow
A GitHub Actions workflow is included at `.github/workflows/smart-build.yml`. It installs Java 17, uses Maven, runs the Java smart-build tool, and shows the detected changes, impacted modules and build order.

- Push to a branch: runs the full build.
- Pull request: compares the PR with its base commit and runs the changed-only smart build.
- Manual run: choose `changed` or `all`; for manual changed mode, set the base branch/ref if it is not `main`.

The workflow is intended as the executable validation pipeline; `build.sh` remains the local command-line entry point.

## Build semantics
The graph direction is `A -> B`, meaning **B depends on A**, so A must be built before B.

For a changed-only build, the tool selects the changed modules plus every downstream module that can be affected by them. Maven is then invoked with `-pl` for the selected reactor projects and `-am` so Maven also includes required reactor dependencies.
