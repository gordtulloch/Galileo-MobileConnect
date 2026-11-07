"""Write api/scenarios/preview.jpg: a synthetic star field with a nebula, so the mock has a frame to show.

    python tools/mock-master/make_preview.py      (needs numpy and Pillow)
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

OUT = Path(__file__).resolve().parents[2] / "api" / "scenarios" / "preview.jpg"
W, H = 1200, 800


def main() -> None:
    rng = np.random.default_rng(42)
    y, x = np.mgrid[0:H, 0:W]
    glow = 60 * np.exp(-(((x - 640) / 240) ** 2 + ((y - 380) / 170) ** 2))
    wisp = 35 * np.exp(-(((x - 540 + (y - 300) * 0.6) / 70) ** 2 + ((y - 460) / 200) ** 2))
    sky = 14 + glow + wisp + rng.normal(0, 2.5, (H, W))
    stars = np.zeros((H, W))
    for _ in range(1400):
        stars[rng.integers(0, H), rng.integers(0, W)] = rng.pareto(1.6) * 60 + 30
    blurred = np.asarray(Image.fromarray(stars.clip(0, 255).astype("uint8")).filter(ImageFilter.GaussianBlur(1.1)), float)
    image = (sky + blurred * 4).clip(0, 255).astype("uint8")
    Image.fromarray(image).save(OUT, quality=82)
    print(OUT, OUT.stat().st_size)


if __name__ == "__main__":
    main()
