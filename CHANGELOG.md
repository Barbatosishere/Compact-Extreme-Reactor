# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
