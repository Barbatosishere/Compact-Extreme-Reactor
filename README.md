# Compact Extreme Reactor

> **English** | [中文](README_zh_CN.md)

Compress Extreme Reactors' large multiblock machines (Reactor / Turbine / Fluidizer) into **single compact blocks** — available for **Forge 1.20.1** and **NeoForge 1.21.1** (multi-version via [Stonecutter](https://stonecutter.kikugie.dev/)).

Place a compact block and install its components through the structure page to operate it. Component counts jointly limit the effective scale of all three machines. Reactors and turbines reuse ER's simulation controllers; fluidizers reuse ER recipes and ZeroCore's recipe processing.

The structure page accepts casings, fuel/control rods, rotor parts/gold coils, and fluidizer injectors. Configuration defines baseline parameters, minimum counts and growth percentages. See [component rules and migration](docs/structure-components.md). Existing saves retain their resources but require minimum components after upgrading.

---

## ✨ Features

- **Compact Reactor** (`compact_reactor`): fuel rods, control rods and power tap counts plus internal size are configurable; power pushed to all 6 adjacent faces each tick (simulated ActivePowerTapFE) with a passive-equivalent FE credit (output varies with core temperature and coolant state; ~0.7k FE/t measured at boiling equilibrium with defaults, higher when running dry). Water piped in via the `IFluidHandler` is vaporized by reactor heat and emitted as steam through the same handler (synchronous fluid path, no FluidPort parts required).
- **Compact Turbine** (`compact_turbine`): rotor / coil scale defined by the simulated layout and config; steam piped in spins up the rotor, which generates FE and exhausts the spent coolant (water) back through the `IFluidHandler` — full steam→FE→water loop.
- **Compact Fluidizer** (`compact_fluidizer`): consumes FE to process ER solid, solid-mixing and fluid-mixing recipes. It has two item inputs, two 8,000 mB fluid inputs and an output tank whose capacity scales with the configured interior volume. Feed ingredients through pipes or by right-clicking with items or fluid containers; the GUI shows mode, progress, inputs and output, with toggle and clear-input controls.
- **Full GUI**: control rod insertion adjustment (−5/+5), machine on/off toggle, void-waste button; live bars for energy / fuel / waste / steam / generated power;
- **Fuel injection**: insert fuel items through the block's item capability (slot 0) or right-click the reactor with them; fluid fuels (such as yellorium/plutonium solutions) and coolant containers use the block's fluid capability;
- **Persistence**: reactor and turbine state is delegated to ER's controller NBT; the fluidizer saves its inputs, output, energy and recipe progress through ZeroCore. Capacities are recalculated from the current configuration on loading.

## ⚙️ How it works

Reactor/turbine simulation architecture:

```
Single-block TileEntity
   └── owns & drives an ER multiblock controller (subclass of MultiblockReactor / MultiblockTurbine)
         ├── isEmpty()/isAssembled() fixed to false/true → bypass real-part assembly checks
         ├── getReferenceCoord() → points to the compact block itself → ZeroCore sync works
         ├── getPartsCount()/getBoundingBox()/getReactorVolume() → return configured simulated values
         └── simulateAssembly() → calls protected onMachineAssembled() → initializes capacities
```

- Every tick `updateMultiblockEntity()` drives the full ReactorLogic / TurbineLogic simulation (radiation, fuel burn, heat, power output, fluid cycle);
- The fluidizer uses its own compact controller and ZeroCore recipe holders to process ER recipes; it does not instantiate ER's multiblock fluidizer controller;
- Energy is exposed via `IEnergyStorage`: reactors and turbines push FE to the 6 neighboring blocks, while fluidizers accept FE only;
- Fluids are exposed via `IFluidHandler`: reactor = coolant/fuel in, steam/waste out; turbine = steam in / water out; fluidizer = ingredients in / recipe output out;
- Control commands (control rod, toggle, void waste, clear fluidizer inputs) go client→server via the platform's networking (NeoForge payloads on 1.21.1, Forge `SimpleChannel` on 1.20.1).

## 📦 Dependencies

### Forge 1.20.1

| Dependency | Version | Notes |
|---|---|---|
| Minecraft | 1.20.1 | Required |
| Forge | 47.1.106 | Required (dev) |
| Extreme Reactors | 1.20.1-2.0.84 | Required (runtime) |
| ZeroCore2 | 1.20.1-2.1.45 | Required (multiblock API) |

### NeoForge 1.21.1

| Dependency | Version | Notes |
|---|---|---|
| Minecraft | 1.21.1 | Required |
| NeoForge | 21.1.x | Required (dev: 21.1.207) |
| Extreme Reactors | 1.21.1-2.4.9 | Required (runtime) |
| ZeroCore2 | 1.21.1-2.4.9 | Required (multiblock API) |

> ⚠️ **Build note**: the `libs/` directory contains local jars of ER / ZeroCore (downloaded from Modrinth because the official Maven `maven.zerono.it` is unreachable). **Do not remove or ignore this directory** or the repo will not compile after cloning.

## 🔨 Building

The project uses Stonecutter to share a single build script across both versions. The active version is set in `stonecutter.gradle.kts`; per-version loader / dependency versions live in `versions/<mc>/gradle.properties`.

```powershell
# JDK 17 (1.20.1) / JDK 21 (1.21.1) — the toolchain is selected automatically
# Build a specific version explicitly:
.\gradlew.bat :1.20.1:build
.\gradlew.bat :1.21.1:build
# Or build the active version:
.\gradlew.bat build
```

Artifacts are at `versions/<mc>/build/libs/compactextremereactor-<version>-<Loader>-<mc>.jar` (e.g. `compactextremereactor-1.0.0-beta4-Forge-1.20.1.jar` / `compactextremereactor-1.0.0-beta4-NeoForge-1.21.1.jar`) — drop the one for your loader into your `mods/` folder.

> 💡 Builds use the official dependency repositories by default. If your local network requires mirrors, add `-Pcer_use_mirrors=true` to use Aliyun, ForgeCDN and BMCLAPI instead. GitHub Actions explicitly uses `-Pcer_use_mirrors=false` so mirror outages do not affect CI. IPv4-first is enabled in `gradle.properties` (`-Djava.net.preferIPv4Stack=true`).

Development runs use `.\gradlew.bat :1.20.1:runServer` or `:1.21.1:runServer` (replace `runServer` with `runClient` for a client). LegacyForge automatically remaps the local 1.20.1 production dependencies to Mojang names; no manual JAR conversion is needed. ER and ZeroCore load from the runtime classpath. Launch tasks remove only their known legacy copies from `run/mods` to avoid duplicate dependencies.

For a separate test world, add `-Pcer_run_directory=build/test-run`; this selects `versions/<mc>/build/test-run` instead of the usual run directory.

GitHub Actions builds both versions and checks the resulting JARs with `tools/verify_artifact.py`, including metadata, Java versions, machine classes, recipes, loot tables and assets. This validates packaging; in-game regressions use the development commands below.

## 🎮 Usage

1. Place a compact reactor / turbine / fluidizer block (creative tab or crafting);
2. Right-click to open the GUI:
   - **Reactor**: the GUI shows status and controls; insert solid fuel through the block's item capability or right-click with fuel; use fluid containers or pipes for coolant and fluid fuels; use `−5/+5` to adjust control rods; toggle button to start/stop; void-waste button to dump nuclear waste;
   - **Turbine**: display-only; feed steam with fluid pipes or containers (e.g. from a reactor's steam output), and collect condensed water through the fluid output;
   - **Fluidizer**: supply FE and recipe ingredients through item/fluid pipes or by right-clicking with ingredient items or fluid containers; extract the resulting fluid through a pipe or container. The GUI shows recipe progress and provides start/stop and clear-input buttons; clearing inputs discards their contents;
3. Draw power from reactors/turbines or supply power to fluidizers with energy cables from any face (all faces are equivalent).

## ⚠️ Known limitations

- Compact reactor radiation uses one upward ray to avoid multiplying simulated heat across six directions. It does not reproduce a real fuel-rod layout or its directional control-rod heat response.
- Reactor and turbine simulation depends on ZeroCore's internal bounding-box field. Compatibility is checked when a machine is assembled; an incompatible API disables that machine through the initialization error handler and records the cause.

Fluid capabilities combine input and output tank views. Tank indices describe contents and validity; the `IFluidHandler.fill/drain` methods do not take a tank index. Filling routes to inputs and draining routes to outputs. A reactor fluid registered as both fuel and coolant is routed as fuel; avoid overlapping mappings when automation must distinguish the two. Untyped reactor draining extracts steam only; extracting waste requires a fluid-specific request.

A turbine with a full FE buffer pauses steam intake, condensation and generation together; after energy extraction it resumes automatically. It does not consume steam while paused, so there is no condensation to replay. Fluidizers select fluid mixing when both input tanks are occupied, then solid mixing or solid processing; dedicate machines to one input mode when automating both kinds of ingredients.

## ⚙️ Config (`config/compactextremereactor-common.toml`)

These values define the baseline simulation. Component requirements use `<machine>.structure.<component>.minimum`; `growthPercent` sets the supported scale increase per extra item.

| Key | Default | Description |
|---|---|---|
| `reactor.fuelRods` | 16 | Simulated fuel rod count (fuel capacity = count × per-rod capacity) |
| `reactor.controlRods` | 4 | Simulated control rod count |
| `reactor.powerTaps` | 4 | Simulated power tap count |
| `reactor.sizeX/Y/Z` | 9 | Simulated reactor size (3–32; energy/fluid buffers scale with volume) |
| `turbine.coilRadius` | 3 | Turbine coil radius (1–16) |
| `turbine.sizeX/Z` | 9 | Simulated turbine size |
| `turbine.sizeY` | 11 | Turbine shaft height (blades = layers × 4) |
| `fluidizer.sizeX/Y/Z` | 9 | Simulated fluidizer size (3–32; output capacity = interior volume × 4,000 mB) |

Configuration reload saves and rebuilds loaded machines using the new baseline and installed components. Shrinking preserves stored fluid; production pauses until enough space is available for a complete recipe result.

Reactor and turbine energy buffers retain all stored FE after shrinking. Generation pauses while the buffer is full; stored energy remains available for extraction.

The fluidizer uses `fluidizer.energyCapacity` and `fluidizer.energyTransfer` as baselines, defaulting to 50,000 FE and 1,000 FE. Both scale with installed components. Existing energy above capacity is retained and can be consumed by recipes; further energy input waits until storage drops below capacity.

Saved inventory sizes cannot change the fluidizer's two item slots or two input tanks. Loading preserves ingredients in valid slots and ignores entries with missing, nonnumeric, fractional or out-of-range slot IDs.

## 📁 Project layout

```
build.gradle                    # shared Stonecutter build script
stonecutter.gradle.kts          # active version switch
libs/                           # local ER / ZeroCore dependencies
tools/
├── rcon-client.ps1            # RCON client used by the soak test
├── soak-test.sh               # RCON-based soak regression test
└── verify_artifact.py         # JAR validation used by CI (Python 3.11+)
src/main/java/com/compact/extremereactor/  # Java sources shared by both versions
└── common/menu/                # Reactor, turbine and fluidizer menus / packed sync
versions/<mc>/                 # per-version sources (1.20.1, 1.21.1)
├── gradle.properties          # loader / dependency versions for this MC
└── src/main/
    ├── java/com/compact/extremereactor/
    │   ├── CompactExtremeReactor.java  # Mod entry point
    │   ├── client/             # Screens and client registration
    │   └── common/             # Blocks, capabilities, config, integration,
    │                           # controllers, network, block entities
    ├── resources/
    │   ├── assets/             # Models, textures, languages, blockstates
    │   └── data/               # Loot tables and recipes
    └── templates/META-INF/    # Mod metadata template (expanded at build)
```

On NeoForge 1.21.1, run `/cerdev selftest <x> <y> <z>` on each machine type in a development server to check capabilities, menu synchronization and persistence. These commands are not registered in production or in the Forge 1.20.1 branch.

The soak test requires Git Bash and PowerShell on Windows, a NeoForge development server with RCON enabled, and a disposable test world. Prepare active turbines at `2950 100 2950` and `2974 100 2950`, a fueled active reactor at `2954 100 2950`, and force-load chunks at `2950 2950` and `2965 2950`. Keep these machines disconnected from power/fluid automation. The test changes machine contents and rebuilds the spare turbine. From the repository root, set the connection parameters and run:

```bash
export CER_RCON_HOST=127.0.0.1
export CER_RCON_PORT=25575
export CER_RCON_PASSWORD='<your test server RCON password>'
bash tools/soak-test.sh
```

The tracked `tools/rcon-client.ps1` is used directly; `rcon-port.ps1` is not required. Generated run directories and soak logs are ignored by Git.

## 🙏 Credits

- [Extreme Reactors](https://github.com/ZeroNoRyouki/ExtremeReactors2) — the compressed machines themselves
- [ZeroCore2](https://github.com/ZeroNoRyouki/ZeroCore2) — multiblock controller framework
- [CompactMekanismMachinesPlus](https://github.com/nanaios/CompactMekanismMachinesPlus) — reference for the single-block compression idea
- [NeoForge](https://docs.neoforged.net/) — modding framework & official docs

## 📜 License

A personal / community mod. It reuses ER & ZeroCore APIs and textures — please respect their original open-source licenses.
