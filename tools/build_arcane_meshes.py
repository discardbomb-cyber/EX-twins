"""Curved Mana petals and distinct black-violet Twins meshes, authored as real geometry."""

import argparse
import itertools
import json
import math

import numpy as np

from build_rf_meshes import Mesh, EVIDENCE, lathe, patch, spherical, surface, unit, closed_radial_shell

MATERIALS = {
    "ceramic": ((.79, .81, .78), 0),
    "ceramic_shadow": ((.41, .48, .55), 0),
    "gold": ((.59, .40, .13), 0),
    "gold_edge": ((.91, .71, .30), 0),
    "navy": ((.024, .04, .09), 0),
    "mana_core": ((.045, .45, .83), .35),
    "mana_light": ((.28, .89, 1), .85),
    "mana_ice": ((.66, .92, 1), .55),
    "obsidian": ((.075, .066, .11), 0),
    "armor": ((.16, .14, .22), 0),
    "armor_edge": ((.31, .28, .38), 0),
    "silver": ((.50, .49, .57), 0),
    "violet": ((.57, .10, .84), .55),
    "amethyst": ((.29, .05, .45), .22),
    "lilac": ((.83, .47, 1), .85),
}


def rotate_faces(mesh, start, axis, angle):
    axis = unit(axis)
    x, y, z = axis
    skew = np.array([[0, -z, y], [z, 0, -x], [-y, x, 0]])
    matrix = np.eye(3) * math.cos(angle) + (1 - math.cos(angle)) * np.outer(axis, axis) + skew * math.sin(angle)
    for i in range(start, len(mesh.faces)):
        group, mat, points, normals = mesh.faces[i]
        mesh.faces[i] = (group, mat, points @ matrix.T, normals @ matrix.T)


def mana_drone():
    mesh = Mesh(MATERIALS)
    mesh.group = "energy_core"
    patch(mesh, 2.40, 0, math.pi, 0, math.tau, "mana_core", 12, 24)
    for latitude in range(1, 6):
        theta = latitude * math.pi / 6
        for segment in range(12):
            phi = segment * math.tau / 12 + latitude * .16
            patch(mesh, 2.418, theta, theta + .025, phi + .025, phi + .37, "mana_light", 1, 3)
    for axis, angle in (([1, 0, 0], .65), ([0, 1, 0], -.65)):
        start = len(mesh.faces)
        lathe(mesh, [(2.62, -.06), (2.72, -.045), (2.72, .045), (2.62, .06)], "gold", segments=48)
        rotate_faces(mesh, start, axis, angle)
    mesh.group = "suspension_collars"
    for side in (-1, 1):
        start = len(mesh.faces)
        lathe(mesh, [(.60, -3.55), (.87, -3.40), (.97, -3.14), (.72, -3.02)], "gold", segments=24)
        lathe(mesh, [(0, -3.575), (.43, -3.57), (.61, -3.55)], "mana_light", segments=24)
        if side > 0:
            rotate_faces(mesh, start, [1, 0, 0], math.pi)
    for shell in range(6):
        mesh.group = f"shell_{shell}_ceramic_petal"
        angle = shell * math.tau / 6
        def point(t, across, offset=0):
            width = .055 + .32 * math.sin(math.pi * t) ** .65
            theta = .41 + t * 2.30
            phi = angle + across * width + .16 * math.sin(t * math.tau)
            return spherical(4.22 + .16 * math.sin(t * math.pi) + offset, theta, phi)
        grid = [[point(row / 12, col / 8 * 2 - 1) for col in range(9)] for row in range(13)]
        closed_radial_shell(mesh, grid, "ceramic", "ceramic_shadow", "gold", .17)
        mesh.group = f"shell_{shell}_gold_inlay"
        for side in (-1, 1):
            surface(mesh, [[point(t, side * a, .065) for a in (.87, .98)] for t in np.linspace(.025, .975, 15)], "gold_edge", True)
        # Long inset blue channels and short crossing ribs read at inventory scale.
        surface(mesh, [[point(t, a, .090) for a in (-.12, .12)] for t in np.linspace(.25, .74, 11)], "navy", True)
        surface(mesh, [[point(t, a, .120) for a in (-.065, .065)] for t in np.linspace(.31, .68, 9)], "mana_light", True)
        for t in (.27, .71):
            surface(mesh, [[point(row, a, .150) for a in (-.39, .39)] for row in (t, t + .019)], "gold", True)
    return mesh


def icosahedron():
    phi = (1 + math.sqrt(5)) / 2
    vertices = np.array([(0, s, t * phi) for s in (-1, 1) for t in (-1, 1)]
                        + [(s, t * phi, 0) for s in (-1, 1) for t in (-1, 1)]
                        + [(t * phi, 0, s) for s in (-1, 1) for t in (-1, 1)], dtype=float)
    vertices /= np.linalg.norm(vertices[0])
    faces = []
    # The twelve known vertices have twenty faces whose three edges are shortest edges.
    edge = min(np.linalg.norm(a - b) for a, b in itertools.combinations(vertices, 2))
    for ids in itertools.combinations(range(12), 3):
        q = vertices[list(ids)]
        if all(abs(np.linalg.norm(a - b) - edge) < 1e-6 for a, b in itertools.combinations(q, 2)):
            if np.dot(np.cross(q[1] - q[0], q[2] - q[0]), q.mean(axis=0)) < 0:
                q = q[::-1]
            faces.append(q)
    assert len(faces) == 20
    return faces


def inset_triangle(mesh, triangle, scale, thickness, color, back_depth=0):
    center = triangle.mean(axis=0)
    normal = unit(center)
    inside = center + (triangle - center) * scale
    front = inside + normal * thickness
    back = inside - normal * back_depth
    mesh.face(front, color, normal)
    mesh.face(back, "obsidian", -normal)
    for i in range(3):
        j = (i + 1) % 3
        mesh.face([back[i], back[j], front[j], front[i]], "armor_edge", (inside[i] + inside[j]) / 2 - center)
    return front


def twins_drone():
    mesh = Mesh(MATERIALS)
    triangles = icosahedron()
    mesh.group = "energy_core"
    for i, q in enumerate(triangles):
        mesh.face(q * 2.50, "violet" if i % 3 else "amethyst", q.mean(axis=0))
        inset_triangle(mesh, q * 2.51, .63, .04, "lilac" if i % 4 == 0 else "amethyst")
    mesh.group = "core_cage"
    for q in triangles:
        center = q.mean(axis=0) * 3.04
        outer = q * 3.04
        inner = center + (outer - center) * .88
        for i in range(3):
            j = (i + 1) % 3
            mesh.face([outer[i], outer[j], inner[j], inner[i]], "obsidian", center)
    # Each closed triangular plate is a separate joint, including its attached trim.
    for i, unit_triangle in enumerate(triangles):
        mesh.group = f"shell_{i}_faceted_armor"
        outer = unit_triangle * 4.35
        center = outer.mean(axis=0)
        normal = unit(center)
        front = inset_triangle(mesh, outer, .86, .12, "armor" if i % 3 else "obsidian", back_depth=.16)
        front_center = front.mean(axis=0)
        inner = front_center + (front - front_center) * .78 + normal * .035
        for edge in range(3):
            j = (edge + 1) % 3
            outer_edge = front_center + (front - front_center) * .81 + normal * .035
            mesh.face([inner[edge], inner[j], outer_edge[j], outer_edge[edge]], "violet", normal)
        # A small triangular embossed plate adds a second depth plane, not a painted decal.
        inset_triangle(mesh, inner + normal * .04, .70, .06, "armor_edge" if i % 4 == 0 else "armor")
    return mesh


def _local_frame(normal):
    """Return stable tangent axes for one suspended Twins armor module."""
    tangent = unit(np.cross([0, 0, 1] if abs(normal[2]) < .88 else [0, 1, 0], normal))
    return tangent, unit(np.cross(normal, tangent))


def _arc_grid(normal, tangent, bitangent, radius, u0, u1, v0, v1, rows, columns, lift=0):
    """A curved rectangular panel whose silhouette reads as a beveled arc, not a flat plate."""
    grid = []
    for row in range(rows + 1):
        u = u0 + (u1 - u0) * row / rows
        line = []
        for column in range(columns + 1):
            v = v0 + (v1 - v0) * column / columns
            # The local radial bulge leaves a deliberate air gap between the core and every arm.
            direction = unit(normal + tangent * math.tan(u) + bitangent * v)
            arc_radius = radius + lift + .10 * math.cos(u * math.pi / max(abs(u0), abs(u1), .01))
            line.append(direction * arc_radius)
        grid.append(line)
    return grid


def twins_shield():
    """Open twenty-arm cage for the Ex-Twins shield item.

    It deliberately shares the old twenty shell group names: the item renderer can keep its
    independently clocked pivots, while the silhouette changes from a closed rock to suspended
    curved armor around an exposed violet core.
    """
    mesh = Mesh(MATERIALS)
    mesh.group = "energy_core"
    patch(mesh, 2.22, 0, math.pi, 0, math.tau, "amethyst", 12, 24)
    patch(mesh, 2.245, .22, math.pi - .22, 0, math.tau, "violet", 8, 24)
    # Three narrow energy rings make the core read as a contained mechanism from every view.
    for axis, angle in (([1, 0, 0], .48), ([0, 1, 0], -.52), ([1, 1, 0], .79)):
        start = len(mesh.faces)
        lathe(mesh, [(2.37, -.045), (2.43, -.025), (2.43, .025), (2.37, .045)], "lilac", segments=40)
        rotate_faces(mesh, start, axis, angle)

    mesh.group = "core_cage"
    # Dark offset hoops visually suspend the core without filling the open gaps between arms.
    for axis, angle in (([1, 0, 0], .18), ([0, 1, 0], -.33)):
        start = len(mesh.faces)
        lathe(mesh, [(2.74, -.075), (2.83, -.045), (2.83, .045), (2.74, .075)], "obsidian", segments=36)
        rotate_faces(mesh, start, axis, angle)

    for index, triangle in enumerate(icosahedron()):
        mesh.group = f"shell_{index}_faceted_armor"
        normal = unit(triangle.mean(axis=0))
        tangent, bitangent = _local_frame(normal)
        # The wide cap is a real closed radial shell. Its slightly bowed arc creates the broken
        # circular armor seen in the reference, with graphite chamfers instead of painted outlines.
        cap = _arc_grid(normal, tangent, bitangent, 4.18, -.30, .30, -.105, .105, 6, 4)
        closed_radial_shell(mesh, cap, "armor" if index % 3 else "obsidian", "obsidian", "armor_edge", .17)
        # A narrower floating jaw gives every module depth and an intentional central void.
        jaw = _arc_grid(normal, tangent, bitangent, 3.57, -.12, .12, .14, .30, 4, 3, lift=.02)
        closed_radial_shell(mesh, jaw, "armor_edge", "obsidian", "amethyst", .14)
        # The bright inset is recessed above the cap so there are no coplanar faces or z-fighting.
        surface(mesh, _arc_grid(normal, tangent, bitangent, 4.185, -.20, .20, -.046, .046, 4, 2, lift=.025),
                "violet", radial=True)
    return mesh


def build(only=None):
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    audit = EVIDENCE / "arcane-mesh-audit.json"
    metadata = json.loads(audit.read_text()) if audit.is_file() else {}
    makers = (("mana_drone", mana_drone, 1.18, 6),
              ("twins_drone", twins_drone, 1.35, 20),
              ("twins_shield", twins_shield, 1.20, 20))
    for item, maker, scale, shells in makers:
        if only is not None and item != only:
            continue
        metadata[item] = maker().export(item, scale)
        metadata[item].update(core_groups=["energy_core"], shell_count=shells)
        if item.startswith("twins_"):
            metadata[item]["facet_centers"] = [q.mean(axis=0).tolist() for q in icosahedron()]
            metadata[item]["scale"] = scale
    (EVIDENCE / "arcane-mesh-audit.json").write_text(json.dumps(metadata, indent=2))
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Export Arcane Relics OBJ meshes")
    parser.add_argument("--only", choices=("mana_drone", "twins_drone", "twins_shield"),
                        help="Export only one mesh; leaves other generated assets untouched")
    print(json.dumps(build(parser.parse_args().only), indent=2))
