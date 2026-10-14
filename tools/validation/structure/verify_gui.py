import argparse
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser()
parser.add_argument("--attempt", default="finalgui2")
args = parser.parse_args()
results = []
for version in ("1.20.1", "1.21.1"):
    folder = ROOT / "build/structure-gui-20261011" / (version + "-" + args.attempt) / "client/screenshots"
    for kind, components in (("reactor", 3), ("turbine", 4), ("fluidizer", 3)):
        for viewport in ("desktop", "small"):
            path = folder / (kind + "-" + viewport + ".png")
            with Image.open(path) as source:
                image = source.convert("RGB")
                width, height = image.size
                x, y = (width - 640) // 2, (height - 460) // 2
                fits = x >= 0 and y >= 0 and x + 684 <= width and y + 460 <= height
                colors = []
                missing = []
                for component in range(components):
                    crop = image.crop((x + 460, y + 88 + component * 44, x + 492, y + 120 + component * 44))
                    palette = crop.getcolors(1024)
                    colors.append(len(palette) if palette else 1024)
                    missing.append(sum(count for count, color in (palette or []) if color == (255, 0, 255)))
                results.append({"file": str(path.relative_to(ROOT)), "size": [width, height],
                                "panelAndNavigationFit": fits, "componentColorCounts": colors,
                                "missingTexturePixels": missing,
                                "passed": fits and min(colors) > 12 and not any(missing)})
output = ROOT / "build/structure-gui-20261011/gui-pixels.json"
output.write_text(json.dumps(results, indent=2), encoding="utf-8")
print(str(sum(result["passed"] for result in results)) + "/" + str(len(results)) + " GUI pixel checks passed")
raise SystemExit(not all(result["passed"] for result in results))
