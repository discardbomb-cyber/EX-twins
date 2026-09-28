"""Build compact whole-drone OBJ meshes for distant RF and Mana swarms.

This imports geometry helpers only.  The existing build modules run their build
pipelines behind ``if __name__ == '__main__'`` and are never invoked here.
"""

from __future__ import annotations

import json
import math

from build_arcane_meshes import MATERIALS as MANA_MATERIALS
from build_arcane_meshes import closed_radial_shell, rotate_faces, spherical
from build_rf_meshes import Mesh, lathe, patch, prism


TAU = math.tau


def rf_drone_lod():
    """A readable silver orb, optic, and four emitter wings in under 500 faces."""
    mesh = Mesh()
    mesh.group = "hull"
    patch(mesh, 3.04, 0, math.pi, 0, TAU, "silver", rows=5, columns=10)
    lathe(mesh, [(3.08, -.13), (3.13, -.045), (3.13, .045), (3.08, .13)], "steel", segments=10)
    lathe(mesh, [(0, -3.21), (.92, -3.06), (.92, -2.91), (.74, -2.81)], "graphite", segments=10)
    lathe(mesh, [(0, -3.225), (.72, -3.13), (.84, -3.01)], "red_glass", segments=8)
    lathe(mesh, [(.93, -3.075), (.80, -3.14)], "red_rim", segments=10)

    for wing in range(4):
        angle = math.pi / 4 + wing * math.pi / 2
        polygon = [(2.85, -.40), (6.10, -.58), (6.80, -.34), (6.80, .34), (6.10, .58), (2.85, .40)]
        prism(mesh, polygon, -.24, .16, "graphite", "steel", angle=angle)
        for radial, lateral in ((4.55, -.22), (5.85, .22)):
            center = (
                radial * math.cos(angle) - lateral * math.sin(angle),
                radial * math.sin(angle) + lateral * math.cos(angle),
                -.31,
            )
            lathe(mesh, [(0, .03), (.26, .03), (.26, -.13), (.19, -.22)], "steel", center=center, segments=6)
            lathe(mesh, [(0, -.225), (.17, -.225), (.14, -.26)], "cyan", center=center, segments=6)
    return mesh


def mana_drone_lod():
    """A turquoise core held by six closed, curved ceramic petals."""
    mesh = Mesh(MANA_MATERIALS)
    mesh.group = "hull"
    patch(mesh, 2.40, 0, math.pi, 0, TAU, "mana_core", rows=4, columns=8)
    for axis, angle in (([1, 0, 0], .65), ([0, 1, 0], -.65)):
        start = len(mesh.faces)
        lathe(mesh, [(2.60, -.065), (2.72, -.04), (2.72, .04), (2.60, .065)], "gold", segments=10)
        # Apply the original's crossed ring orientation without importing its build routine.
        rotate_faces(mesh, start, axis, angle)

    for shell in range(6):
        angle = shell * TAU / 6

        def point(t, across, offset=0):
            width = .055 + .32 * math.sin(math.pi * t) ** .65
            theta = .41 + t * 2.30
            phi = angle + across * width + .16 * math.sin(t * TAU)
            return spherical(4.22 + .16 * math.sin(t * math.pi) + offset, theta, phi)

        # Four longitudinal rows and two columns preserve the original petal silhouette.
        grid = [[point(row / 4, column / 2 * 2 - 1) for column in range(3)] for row in range(5)]
        closed_radial_shell(mesh, grid, "ceramic", "ceramic_shadow", "gold", .17)
    return mesh


def main():
    metadata = {
        "rf_drone_lod": rf_drone_lod().export("rf_drone_lod", 1.0),
        "mana_drone_lod": mana_drone_lod().export("mana_drone_lod", 1.18),
    }
    for name, record in metadata.items():
        if record["faces"] > 500:
            raise ValueError(f"{name} exceeds the 500 face budget: {record['faces']}")
    print(json.dumps(metadata, indent=2))
    return metadata


if __name__ == "__main__":
    main()
