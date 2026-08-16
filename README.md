# Compact Extreme Reactor

> **English** | [中文](README_zh_CN.md)

Compress Extreme Reactors' large multiblock machines (Reactor / Turbine) into **single compact blocks** — available for **Forge 1.20.1** and **NeoForge 1.21.1** (multi-version via [Stonecutter](https://stonecutter.kikugie.dev/)).

Place one block and get an entire functional multiblock machine, fully reusing ER's reactor / turbine simulation logic without building any real structure.

---

## ✨ Features

- **Compact Reactor** (`compact_reactor`): fuel rods, control rods and power tap counts plus internal size are configurable; water in via fluid capability, steam out via fluid capability, power pushed to all 6 adjacent faces each tick (simulated ActivePowerTapFE);
- **Compact Turbine** (`compact_turbine`): steam in via fluid capability, condensate out; rotor / coil scale defined by the simulated layout and config; no steam → no spin;
- **Full GUI**: control rod insertion adjustment (−5/+5), machine on/off toggle, void-waste button; live bars for energy / fuel / waste / steam / generated power;
- **Auto fuel injection**: put a fuel item (e.g. yellorium ingot) into the GUI fuel slot — it is automatically mapped to a `Reactant` and inserted into the fuel container;
- **Save-compatible**: NBT is delegated to the ER controller (`syncDataFrom` / `syncDataTo`), same save format as ER; capacities are recomputed on simulated assembly.

## ⚙️ How it works

Single-block simulation architecture:

```
Single-block TileEntity
   └── owns & drives an ER multiblock controller (subclass of MultiblockReactor / MultiblockTurbine)
         ├── isEmpty()/isAssembled() fixed to false/true → bypass real-part assembly checks
         ├── getReferenceCoord() → points to the compact block itself → ZeroCore sync works
         ├── getPartsCount()/getBoundingBox()/getReactorVolume() → return configured simulated values
         └── simulateAssembly() → calls protected onMachineAssembled() → initializes capacities
```

- Every tick `updateMultiblockEntity()` drives the full ReactorLogic / TurbineLogic simulation (radiation, fuel burn, heat, power output, fluid cycle);
- Energy is exposed via `IEnergyStorage` and actively pushed to the 6 neighboring blocks each tick;
- Fluids are exposed via `IFluidHandler`: reactor = water in / steam out; turbine = steam in / water out;
- Control commands (control rod, toggle, void waste) go client→server via the platform's networking (NeoForge payloads on 1.21.1, Forge `SimpleChannel` on 1.20.1).

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

> 💡 If dependency downloads fail on a flaky network, IPv4-first is already enabled in `gradle.properties` (`-Djava.net.preferIPv4Stack=true`) and a reachable mirror (`neoforged.forgecdn.net`, BMCLAPI) is configured as fallback in `build.gradle`.

## 🎮 Usage

1. Place a compact reactor / turbine block (creative tab or crafting);
2. Right-click to open the GUI:
   - **Reactor**: put fuel items (yellorium ingot etc.) into the fuel slot for auto-injection; use `−5/+5` to adjust control rods; toggle button to start/stop; void-waste button to dump nuclear waste;
   - **Turbine**: display-only; feed steam with fluid pipes (e.g. from a reactor's steam output);
3. Draw power with energy cables / conduits from any face (all faces are equivalent on a single block).

## ⚙️ Config (`config/compactextremereactor-common.toml`)

| Key | Default | Description |
|---|---|---|
| `reactorFuelRods` | 3 | Simulated fuel rod count |
| `reactorControlRods` | 3 | Simulated control rod count |
| `reactorPowerTaps` | 6 | Simulated power tap count |
| `reactorSizeX/Y/Z` | 5 | Simulated reactor shell size |
| `turbineCoilRadius` | 2 | Turbine coil radius |
| `turbineSizeX/Y/Z` | 5 | Simulated turbine shell size |

## 📁 Project layout

```
versions/<mc>/                 # per-version sources (1.20.1, 1.21.1)
├── gradle.properties          # loader / dependency versions for this MC
└── src/main/
    ├── java/com/compact/extremereactor/
    │   ├── CompactExtremeReactor.java  # Main class: capability/payload registration, config
    │   ├── client/
    │   │   ├── ClientHandler.java      # Client screen registration
    │   │   └── screen/                 # Reactor / Turbine GUIs
    │   ├── common/
    │   │   ├── Content.java            # Block/Item/BE/Menu registry
    │   │   ├── block/                  # Block classes (GUI open, BE binding)
    │   │   ├── capability/             # Energy / fluid capability wrappers
    │   │   ├── config/CompactConfig.java # Simulation parameters
    │   │   ├── menu/                   # Containers (data-slot sync + auto fuel injection)
    │   │   ├── multiblock/             # Simulated controllers (core: ER controller subclasses)
    │   │   ├── network/ModPackets.java # C2S control payloads
    │   │   └── tile/                   # TileEntities (controller lifecycle/NBT/capabilities)
    │   └── resources/
    │       ├── assets/                 # Models / lang (en_us, zh_cn) / blockstates
    │       ├── data/                   # Loot tables / recipes
    │       └── templates/META-INF/mods.toml # Mod metadata template (expanded at build)
├── build.gradle               # shared build script (repo root)
└── stonecutter.gradle.kts     # active version switch
```

## 🙏 Credits

- [Extreme Reactors](https://github.com/ZeroNoRyouki/ExtremeReactors2) — the compressed machines themselves
- [ZeroCore2](https://github.com/ZeroNoRyouki/ZeroCore2) — multiblock controller framework
- [CompactMekanismMachinesPlus](https://github.com/nanaios/CompactMekanismMachinesPlus) — reference for the single-block compression idea
- [NeoForge](https://docs.neoforged.net/) — modding framework & official docs

## 📜 License

A personal / community mod. It reuses ER & ZeroCore APIs and textures — please respect their original open-source licenses.
