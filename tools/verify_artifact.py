"""Validate distributable mod JARs, including version-specific resource paths."""

import argparse
import json
import struct
import sys
import tomllib
from pathlib import Path
from zipfile import BadZipFile, ZipFile


MOD_ID = "compactextremereactor"
PACKAGE = "com/compact/extremereactor/"
MACHINES = {"reactor": "Reactor", "turbine": "Turbine", "fluidizer": "Fluidizer"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify(jar_path, minecraft_version):
    forge = minecraft_version == "1.20.1"
    loader = "Forge" if forge else "NeoForge"
    metadata_path = "META-INF/mods.toml" if forge else "META-INF/neoforge.mods.toml"
    recipe_dir, loot_dir = ("recipes", "loot_tables") if forge else ("recipe", "loot_table")
    class_version = 61 if forge else 65
    properties = dict(
        line.split("=", 1)
        for line in (Path(__file__).resolve().parents[1] / "gradle.properties")
        .read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    )

    with ZipFile(jar_path) as jar:
        require(jar.testzip() is None, "JAR contains a corrupt entry")
        names = jar.namelist()
        require(len(names) == len(set(names)), "JAR contains duplicate entries")
        require(metadata_path in names, f"Missing {metadata_path}")
        metadata_text = jar.read(metadata_path).decode("utf-8")
        require("${" not in metadata_text, "Metadata contains unexpanded placeholders")
        metadata = tomllib.loads(metadata_text)
        mod = next((entry for entry in metadata["mods"] if entry["modId"] == MOD_ID), None)
        require(mod is not None, "Incorrect mod ID")
        require(mod["version"] == properties["mod_version"], "Metadata version differs from gradle.properties")
        require(jar_path.name == f"{MOD_ID}-{mod['version']}-{loader}-{minecraft_version}.jar",
                "Incorrect distributable JAR filename")
        dependencies = {entry["modId"]: entry for entry in metadata["dependencies"][MOD_ID]}
        for dependency in ("minecraft", loader.lower(), "bigreactors", "zerocore"):
            require(dependency in dependencies, f"Missing dependency: {dependency}")
            entry = dependencies[dependency]
            require(entry.get("mandatory") is True if forge else entry.get("type") == "required",
                    f"Dependency is not required: {dependency}")
            require(bool(entry.get("versionRange")), f"Missing version range: {dependency}")

        require(not any(name.startswith("it/zerono/") or name.endswith(".jar") for name in names),
                "Runtime dependency classes or JARs were embedded")
        json_data = {name: json.loads(jar.read(name)) for name in names if name.endswith(".json")}
        languages = [json_data[f"assets/{MOD_ID}/lang/{language}.json"] for language in ("en_us", "zh_cn")]
        require(languages[0].keys() == languages[1].keys(), "English/Chinese translation keys differ")

        required_classes = ["CompactExtremeReactor", "common/Content", "common/menu/PackedContainerData"]
        required_classes.extend([
            "common/menu/CompactStructureMenu", "common/structure/StructureKind",
            "common/structure/StructureInventory", "common/structure/StructureState",
            "client/screen/CompactStructureScreen", "client/screen/CompactStructureScreenBase",
        ])
        require(not any(name.startswith("cerstructure/") for name in names), "Validation probe included in JAR")
        for machine, class_name in MACHINES.items():
            block = f"compact_{machine}"
            required_classes.extend([
                f"common/block/Compact{class_name}Block",
                f"common/tile/Compact{class_name}TileEntity",
                f"common/multiblock/Compact{class_name}Controller",
                f"common/menu/Compact{class_name}Menu",
                f"client/screen/Compact{class_name}Screen",
            ])
            recipe_path = f"data/{MOD_ID}/{recipe_dir}/{block}.json"
            loot_path = f"data/{MOD_ID}/{loot_dir}/blocks/{block}.json"
            require(recipe_path in json_data, f"Missing recipe: {recipe_path}")
            require(json_data[recipe_path]["result"].get("item" if forge else "id") == f"{MOD_ID}:{block}",
                    f"Incorrect recipe result: {recipe_path}")
            require(loot_path in json_data, f"Missing loot table: {loot_path}")
            entries = [entry for pool in json_data[loot_path].get("pools", []) for entry in pool.get("entries", [])]
            require(any(entry.get("name") == f"{MOD_ID}:{block}" for entry in entries),
                    f"Machine does not drop itself: {loot_path}")
            for resource in (f"blockstates/{block}.json", f"models/block/{block}.json",
                             f"models/item/{block}.json", f"textures/gui/{block}.png",
                             *(f"textures/block/{block}_{face}.png" for face in ("front", "side", "top", "bottom"))):
                require(f"assets/{MOD_ID}/{resource}" in names, f"Missing resource: {resource}")
            for language in languages:
                require(bool(language.get(f"block.{MOD_ID}.{block}")), f"Missing machine name: {block}")

        dead_directories = ("recipe", "loot_table") if forge else ("recipes", "loot_tables")
        require(not any(name.startswith(tuple(f"data/{MOD_ID}/{directory}/" for directory in dead_directories))
                        for name in names), "Resources use directories from the wrong Minecraft version")
        for class_name in required_classes:
            require(f"{PACKAGE}{class_name}.class" in names, f"Missing machine/shared class: {class_name}")
        for name in names:
            if name.startswith(PACKAGE) and name.endswith(".class"):
                magic, _, major = struct.unpack(">IHH", jar.read(name)[:8])
                require(magic == 0xCAFEBABE and major == class_version, f"Incorrect Java class version: {name}")

    print(f"PASS: {jar_path.name} (metadata, classes, recipes, loot, assets, translations)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--minecraft-version", required=True, choices=("1.20.1", "1.21.1"))
    args = parser.parse_args()
    try:
        verify(args.jar, args.minecraft_version)
    except (OSError, BadZipFile, ValueError, KeyError, struct.error) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
