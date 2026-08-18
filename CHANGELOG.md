# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-beta6] - 2026-08-17

### Fixed

- **GUI toggle button (开关) had no response on newly placed machines** — `ReactorData.get()` / `TurbineData.get()` returned 0 for all data slots including `DATA_POS_READY` when the controller was null, which kept the toggle/control-rod buttons disabled in the GUI. Position data is now resolved before the controller null check, so buttons are always enabled.
- **Newly placed machines (setblock / player placement) did not initialize their controller** — the controller initialization was deferred to the first `serverTick()`, but the ticker registration path was unreliable for `setblock`-placed block entities. Both `setLevel()` and `onLoad()` now call `initController()` proactively, and `setChanged()` ensures the initial state (active, capacities, control rod ratio) is persisted immediately.
- All Chinese "压缩反应堆" references renamed to "压缩极限反应堆" (block lang, README_zh_CN, Javadocs) for both 1.20.1 and 1.21.1.

### Changed

- `AbstractCompactMachineTileEntity` now overrides `setLevel()` to initialize the controller when the block entity is first attached to a world, ensuring setblock and player-placed machines work identically.
- `DATA_POS_READY` and block position data are now served independently of the controller state in both `ReactorData` and `TurbineData`, so the GUI is always functional even if the controller hasn't been created yet.

## [1.0.0-beta5] - 2026-08-16

### Fixed

- **Crash on world tick with ER2 2.4.21+** (`IndexOutOfBoundsException: Index: 0, Size: 0`, crash-2026-08-16_22.51.44): ER2's `onMachineAssembled()` → `createFuelRodsLayout()` → `getControlRodByIndex(0)` indexes the (empty) attached-control-rod list after bounds-checking against the *simulated* `getControlRodsCount()`. The compact controller now overrides `getControlRodByIndex` to return `Optional.empty()` — matching the "part does not exist" contract; the layout builder then falls back to `Direction.UP` safely. This crashed instantly on placement with ER2 2.4.28, and after ~10 s via the delayed layout task on older versions.
- **Energy buffer capacity was always 0** (both versions): ER's `onMachineAssembled()` sizes the buffer as `per-part capacity × getPartsCount()` (no-arg), which returned 0 for the part-less compact controllers. Both compact controllers now override the no-arg `getPartsCount()` with the simulated structure block count — the GUI energy bar and `getMaxEnergyStored()` report real values again.
- **Reactor generated no energy: fuel heat never reached the reactor body** — ER2's fuel→reactor heat transfer coefficient is the sum of *real* fuel rod conductivities (0 without parts), so fuel burned but reactor heat (and thus power) stayed at zero. The compact controller now simulates the real-world approximation (4 exposed faces × air conductivity × simulated rod count). Verified live: reactor heat climbs, FE accumulates to the full 7.29M buffer.
- **FE compensation was silently rejected** — ER2 generator buffers ship with `maxInsert=0` and old saves restore that value; the buffer now has insertion force-reopened after every NBT restore (`syncDataFrom`) so the passive-equivalent FE credit actually lands.
- **Forge 1.20.1 jar was rejected at load** — the mods.toml used the NeoForge-style `type="required"` dependency field; Forge 1.20.1 requires `mandatory=true`. All dependencies in the 1.20.1 template were converted.
- **NeoForge 1.21.1 dev environment failed to load the mod** — metadata file renamed from legacy `META-INF/mods.toml` to `META-INF/neoforge.mods.toml` (both names work in production, only the canonical one passes the dev classpath scanner).
- **Fuel bar capacity wrong on Forge 1.20.1**: `getFuelCapacity()` returned the *energy* buffer capacity instead of the fuel container capacity (`MultiblockReactor.getCapacity()` no-arg); now matches the 1.21.1 implementation.

### Known limitations

- **Reactor water→steam conversion and the compact turbine do not produce steam power yet.** Steam can be stored in the fluid tanks (verified), but ER2's vaporization/rotor-spin paths depend on deeper part-based internals that the single-block simulation does not currently provide (inductor engagement, rotor flow conditions). The compact reactor outputs FE (verified at full 7.29M buffer / ~3.7k FE/t with default config). Steam support is on the roadmap.
- Forge 1.20.1 `runServer`/`runClient` dev runs fail because the local ZeroCore/ER2 jars are production (SRG-mapped) builds; production installs are unaffected.

### Changed

- Forge 1.20.1 capability providers now cache their `LazyOptional`s and invalidate them in `invalidateCaps()` instead of creating a new wrapper on every query.
- The GUI fuel slot only accepts items mappable to an ER reactant (`ReactantMappingsRegistry`), and fuel consumption no longer can shrink a stack below zero.
- README / README_zh_CN: config key names and defaults corrected to match the code (`reactor.fuelRods=16`, sizes 9×9×9 / 9×11×9, coil radius 3); FE output documented; steam cycle marked as not yet available.

### Verified (live 1.21.1 dedicated-server simulation)

- Block placement and 5+ minutes of fueled ticking with zero exceptions (the exact path that crashed with ER2 2.4.28).
- Energy buffer capacity 7,290,000 FE (reactor) / 8,910,000 FE (turbine) — previously always 0.
- Fuel burn, heat transfer (fuel 4300° → reactor 570°→2400°), FE compensation filling the buffer to capacity, control-rod ratio persistence.

## [1.0.0-beta4] - 2026-08-16

### Added

- **Forge 1.20.1 support**: the mod now builds and runs on both Forge 1.20.1 (47.1.106) and NeoForge 1.21.1 via a Stonecutter multi-version setup.
- **Multi-version project structure**: sources moved to `versions/1.20.1` and `versions/1.21.1`, with per-version `gradle.properties` for loader / dependency versions; `stonecutter.gradle.kts` switches the active version.

### Fixed

- 1.20.1 API differences against the NeoForged Forge 47.1.x branch: `Capabilities` → `ForgeCapabilities`, `loadAdditional()` → `load()`, `registerConfig()` → `addConfig()`, `LazyOptional.ofNullable()` → `of()` with null check, removed `Level.getCapability(cap, pos, dir)`, removed `RegisterMenuScreensEvent` (now `MenuScreens.register` in `FMLClientSetupEvent`), `MenuType` two-arg constructor, `Registries.CREATIVE_MODE_TAB`, `Entity.level()` accessor.

## [1.0.0-beta3] - 2026-08-12

### Added

- **Compact Reactor** block: compresses a full ER reactor (fuel rods, control rods, power taps, internal size — all configurable) into a single block.
- **Compact Turbine** block: compresses a full ER turbine (rotor with blades, gold coil, steam/condensate cycle) into a single block.
- Full GUI: live bars for energy / fuel / waste / steam / generated power, control rod insertion adjustment (−5/+5), machine on/off toggle, void-waste button.
- Auto fuel injection: a fuel item (e.g. yellorium ingot) placed in the GUI fuel slot is mapped to a `Reactant` and inserted into the fuel container.
- Capabilities: `IEnergyStorage` (power pushed to all 6 adjacent faces every tick, simulated `ActivePowerTapFE`) and `IFluidHandler` (reactor: water in / steam out; turbine: steam in / water out).
- NBT save format delegated to the ER controller (`syncDataFrom` / `syncDataTo`), same format as ER.

### Fixed

- Control rod insertion ratio is now persisted to NBT (previously reset to 0 after world reload).
- Empty NBT no longer overwrites existing saves when the controller is not yet initialized (previously a fresh `controller` tag could erase saved data).
- Toggling the machine on/off now marks the block entity dirty so the state survives chunk unload.
- `mods.toml` `loaderVersion` corrected to NeoForge `[2,)` so the mod is actually loaded by the NeoForge 2.x mod discoverer.

## [1.0.7-beta2] - 2026-08-12

### Fixed

- Persist control rod insertion ratio; prevent empty NBT overwrite of saves; mark block dirty on active toggle.

## [1.0.7-beta1] - 2026-08-11

### Added

- Initial single-block simulation architecture based on Extreme Reactors 2 / ZeroCore 2 (NeoForge 1.21.1).
