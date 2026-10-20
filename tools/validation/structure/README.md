# Structure Validation

Windows development tests for Forge 1.20.1 and NeoForge 1.21.1. Runtime worlds are created in the OS temporary directory; evidence is written under `build/structure-gui-20261011`. Run versions and suites sequentially with ports 25590 and 25591 available.

The probe stays outside the production source set. The init script also generates the direct-launch classpaths from the current Gradle run tasks, so a fresh checkout needs no copied runtime files. Override `CER_JAVA17` and `CER_JAVA21` when the local Java installations differ from the defaults in `run_server.py`.

```powershell
.\gradlew.bat :1.20.1:build :1.21.1:build :1.20.1:compileStructureProbeJava :1.21.1:compileStructureProbeJava :1.20.1:processStructureProbeResources :1.21.1:processStructureProbeResources :1.20.1:prepareServerRun :1.21.1:prepareServerRun :1.20.1:prepareClientRun :1.21.1:prepareClientRun --offline --console=plain -I tools/validation/structure/probe.init.gradle
python tools/validation/structure/run_server.py --attempt local-server
python tools/validation/structure/run_gui.py --attempt local-gui
python tools/validation/structure/verify_gui.py --attempt local-gui
```

The last check uses Pillow. GUI checks require an interactive desktop and a working OpenGL driver.

If Minecraft's remote blocklist request prevents the client from connecting to localhost, `run_gui.py --offline-address-check` creates a separate test-only Patchy copy without that service provider. Original dependencies, Gradle caches and production JARs are preserved. This option tests local menu behavior and does not cover remote address checks.

The server assertions cover coupled component limits, capacity growth, recipe processing, shrinking with resources above capacity, invalidated references, save/load, configuration reload, ordinary inventory actions, six-side FE conservation, exact component drops, distance checks, actual chunk reload and actual server restart. Generator fluid checks cover small structures below the ER variant limit, fractional height rounding and large structures at the limit; doubling scale does not require doubling a capped tank. Drop checks cover all three machines with full, mixed, empty and covered inventories, differing NBT/components, damaged nonstackable items, stack limits and repeated removal. Newly spawned drops are captured through the entity join event because same-command spatial queries can precede entity tracking. The 12 client checks cover all three machines at 1280x720 and 854x480, real network item transfers, page navigation and distance closure.

`BoundaryChecks` additionally verifies generator tank shrinkage without fluid loss or caller-NBT mutation, and temporarily installs custom ER fluid-mixing recipes in the isolated test server. It checks equal and unequal quantities of the same fluid, exact-batch processing, restored tank order, read-only simulations, oversized fills, recipe replacement/removal and reserving a tank for different-fluid recipes. The original recipe cache is restored in a `finally` block.

For the 3000-machine, ten-cycle replacement comparison with normal drops retained:

```powershell
python tools/validation/structure/run_global_current.py --attempt UNIQUE-drops --version all --kind drops --output-root $env:TEMP/CER-validation
```

This driver requires the archived stress probe sources and init scripts under `build/comprehensive-stress-20261009` and `build/global-stress-20261011`. Compile both `stressProbe` source sets and prepare the runtime arguments before running it. Samples use 600 game ticks, include probe automation and use a 2 GiB server heap. Run comparisons sequentially; `drops` retains item entities throughout, while `entities` includes a separate test-only cleanup control. Neither simulates networked clients.
