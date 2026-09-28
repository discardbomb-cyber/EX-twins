"""Native OBJ geometry for the silver RF sphere and its four-panel drone form."""

from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "src/main/resources/assets/relics_addon/models/item"
TEXTURES = ROOT / "src/main/resources/assets/relics_addon/textures/item/materials"
EVIDENCE = ROOT / "reports/evidence/ASSETS-P04"
TAU = math.tau
MATERIALS = {
    "silver": ((.47, .51, .55), 0),
    "pearl": ((.70, .73, .76), 0),
    "edge": ((.92, .95, .98), 0),
    "graphite": ((.045, .055, .068), 0),
    "rubber": ((.019, .024, .032), 0),
    "steel": ((.24, .28, .33), 0),
    "red_rim": ((.34, .012, .025), 0),
    "red_glass": ((.78, .012, .035), .28),
    "iris": ((.17, .022, .028), 0),
    "copper": ((.40, .24, .13), 0),
    "cyan": ((.32, .77, 1.0), 1),
    "white": ((.93, .98, 1.0), 1),
}
for tone in range(16):
    t = tone / 15
    MATERIALS[f"lens_{tone:02}"] = ((.14 + .80 * t, .006 + .38 * max(0, (t - .70) / .30),
                                    .018 + .30 * max(0, (t - .70) / .30)), .035)


def unit(v):
    v = np.asarray(v, dtype=float)
    length = np.linalg.norm(v)
    if length < 1e-9:
        raise ValueError("Degenerate normal")
    return v / length


class Mesh:
    def __init__(self, materials=None):
        self.faces = []
        self.group = "hull"
        self.materials = materials or MATERIALS

    def face(self, points, material, outward, normals=None):
        p = np.asarray(points, dtype=float)
        normal = np.cross(p[1] - p[0], p[2] - p[0])
        if np.linalg.norm(normal) < 1e-9:
            return
        if np.dot(normal, outward) < 0:
            p = p[::-1]
            normals = normals[::-1] if normals is not None else None
            normal = -normal
        normals = np.asarray(normals) if normals is not None else np.tile(unit(normal), (len(p), 1))
        self.faces.append((self.group, material, p, normals))

    def export(self, item, scale):
        vertices, normals, faces = [], [], []
        last_group = last_material = None
        group_order = {name: i for i, name in enumerate(dict.fromkeys(f[0] for f in self.faces))}
        # One contiguous OBJ group per joint, including polyhedron faces emitted in spatial order.
        for group, material, points, ns in sorted(self.faces, key=lambda face: group_order[face[0]]):
            if group != last_group:
                faces.append(f"g {group}")
                last_group = group
            if material != last_material:
                faces.append(f"usemtl {material}")
                last_material = material
            indices = []
            for point, normal in zip(points, ns):
                point = (point * scale + 8) / 16
                vertices.append("v " + " ".join(f"{x:.7f}" for x in point))
                normals.append("vn " + " ".join(f"{x:.7f}" for x in normal))
                index = len(vertices)
                indices.append(f"{index}/1/{index}")
            faces.append("f " + " ".join(indices))
        (MODELS / f"{item}.obj").write_text("\n".join([
            "# Relics-P04; model coordinates in block units; front faces -Z",
            f"mtllib {item}.mtl", *vertices, "vt 0.5 0.5", *normals, *faces, "",
        ]), encoding="ascii")
        mtl = []
        for name, (color, emission) in self.materials.items():
            mtl += [f"newmtl {name}", "Kd " + " ".join(map(str, color)),
                    f"Ka {emission} {emission} {emission}",
                    "map_Kd relics_addon:item/materials/rf_mesh_white", ""]
        (MODELS / f"{item}.mtl").write_text("\n".join(mtl), encoding="ascii")
        points = np.concatenate([face[2] for face in self.faces]) * scale
        return {"faces": len(self.faces), "vertices": len(vertices),
                "bounds_model_units": [list(points.min(axis=0) + 8), list(points.max(axis=0) + 8)],
                "groups": sorted({face[0] for face in self.faces})}


def spherical(radius, theta, phi):
    return np.array([radius * math.sin(theta) * math.cos(phi),
                     radius * math.sin(theta) * math.sin(phi), -radius * math.cos(theta)])


def patch(mesh, radius, t0, t1, p0, p1, material, rows=6, columns=6, bevel=False):
    grid = [[spherical(radius, t0 + (t1 - t0) * i / rows, p0 + (p1 - p0) * j / columns)
             for j in range(columns + 1)] for i in range(rows + 1)]
    for i in range(rows):
        for j in range(columns):
            points = [grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]]
            # At a pole collapse one duplicate point to a triangle.
            unique = []
            for point in points:
                if not any(np.linalg.norm(point - other) < 1e-8 for other in unique):
                    unique.append(point)
            if len(unique) >= 3:
                mesh.face(unique, material, np.mean(unique, axis=0), [unit(p) for p in unique])
    if bevel:
        boundary = grid[0] + [r[-1] for r in grid[1:]] + list(reversed(grid[-1][:-1])) + [r[0] for r in reversed(grid[1:-1])]
        for a, b in zip(boundary, boundary[1:] + boundary[:1]):
            mesh.face([a, b, b * .975, a * .975], "silver", (a + b) / 2)


def lathe(mesh, profile, material, center=(0, 0, 0), segments=32, span=(0, TAU), smooth=False):
    c = np.asarray(center)
    if profile[0][0] > profile[-1][0]:
        profile = list(reversed(profile))
    directions = [unit([q[1] - p[1], p[0] - q[0]]) for p, q in zip(profile, profile[1:])]
    for k, ((r0, z0), (r1, z1)) in enumerate(zip(profile, profile[1:])):
        for i in range(segments):
            a, b = (span[0] + (span[1] - span[0]) * j / segments for j in (i, i + 1))
            p = [c + (r0 * math.cos(a), r0 * math.sin(a), z0),
                 c + (r0 * math.cos(b), r0 * math.sin(b), z0),
                 c + (r1 * math.cos(b), r1 * math.sin(b), z1),
                 c + (r1 * math.cos(a), r1 * math.sin(a), z1)]
            normal = np.array([(z1 - z0) * math.cos((a + b) / 2),
                               (z1 - z0) * math.sin((a + b) / 2), r0 - r1])
            ns = None
            if smooth:
                n0 = unit(directions[max(0, k - 1)] + directions[k])
                n1 = unit(directions[k] + directions[min(len(directions) - 1, k + 1)])
                ns = [np.array([n[0] * math.cos(phi), n[0] * math.sin(phi), n[1]])
                      for n, phi in ((n0, a), (n0, b), (n1, b), (n1, a))]
            if r0 == 0:
                p = [p[0], p[2], p[3]]
                if ns is not None:
                    ns = [np.array([0, 0, -1]), ns[2], ns[3]]
            elif r1 == 0:
                p = p[:3]
                if ns is not None:
                    ns = [ns[0], ns[1], np.array([0, 0, -1])]
            mesh.face(p, material, normal, ns)


def surface(mesh, grid, material, radial=False, back=False):
    for i in range(len(grid) - 1):
        for j in range(len(grid[0]) - 1):
            points = [grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]]
            sign = -1 if back else 1
            outward = np.mean(points, axis=0) * sign if radial else [0, 0, 1 if back else -1]
            mesh.face(points, material, outward, [unit(p) * sign for p in points] if radial else None)


def closed_radial_shell(mesh, grid, front_material, back_material, edge_material, thickness):
    """Close a curved panel with matching inner vertices and consistently wound edge walls."""
    inner = [[p - unit(p) * thickness for p in row] for row in grid]
    surface(mesh, grid, front_material, radial=True)
    surface(mesh, inner, back_material, radial=True, back=True)
    def boundary(values):
        return (values[0] + [row[-1] for row in values[1:]] + list(reversed(values[-1][:-1]))
                + [row[0] for row in reversed(values[1:-1])])
    outer_loop, inner_loop = boundary(grid), boundary(inner)
    original_normal = np.cross(grid[1][0] - grid[0][0], grid[1][1] - grid[0][0])
    sign = 1 if np.dot(original_normal, grid[0][0]) > 0 else -1
    for index, a in enumerate(outer_loop):
        following = (index + 1) % len(outer_loop)
        points = [a, outer_loop[following], inner_loop[following], inner_loop[index]]
        normal = np.cross(points[1] - points[0], points[2] - points[0]) * sign
        mesh.face(points, edge_material, normal)


def armor_petal(mesh, t0, t1, angle, width, material, radius=3.24):
    grid = []
    for i in range(9):
        t = i / 8
        half_width = width - .12 * abs(2 * t - 1) ** 6
        theta = t0 + (t1 - t0) * t
        grid.append([spherical(radius, theta, angle + (j / 8 * 2 - 1) * half_width + .08 * math.sin(t * math.pi))
                     for j in range(9)])
    closed_radial_shell(mesh, grid, material, "graphite", "silver", .075)


def oriented_lathe(mesh, profile, material, center, axis, segments=20, flip=False):
    start = len(mesh.faces)
    lathe(mesh, profile, material, segments=segments)
    z = unit(axis)
    x = unit(np.cross([0, 0, 1], z))
    y = np.cross(z, x)
    matrix = np.column_stack((x, y, z))
    for i in range(start, len(mesh.faces)):
        group, mat, points, normals = mesh.faces[i]
        if flip:
            points, normals = points[::-1], -normals[::-1]
        mesh.faces[i] = (group, mat, points @ matrix.T + center, normals @ matrix.T)


def prism(mesh, polygon, front, back, material, edge="steel", angle=0, offset=(0, 0, 0)):
    co, si = math.cos(angle), math.sin(angle)
    def point(p, z):
        return np.array([p[0] * co - p[1] * si, p[0] * si + p[1] * co, z]) + offset
    front_points, back_points = [point(p, front) for p in polygon], [point(p, back) for p in polygon]
    for i in range(1, len(polygon) - 1):
        mesh.face([front_points[0], front_points[i], front_points[i + 1]], material, [0, 0, -1])
        mesh.face([back_points[0], back_points[i], back_points[i + 1]], material, [0, 0, 1])
    middle = np.mean(front_points, axis=0)
    for i in range(len(polygon)):
        j = (i + 1) % len(polygon)
        out = (front_points[i] + front_points[j]) / 2 - middle
        out[2] = 0
        mesh.face([front_points[i], front_points[j], back_points[j], back_points[i]], edge, out)


def rectangle(x0, x1, y0, y1, corner=.08):
    return [(x0 + corner, y0), (x1 - corner, y0), (x1, y0 + corner), (x1, y1 - corner),
            (x1 - corner, y1), (x0 + corner, y1), (x0, y1 - corner), (x0, y0 + corner)]


def body(mesh):
    mesh.group = "graphite_inner_sphere"
    # Leave the aperture open: a complete inner sphere would occlude the recessed lens.
    patch(mesh, 3.06, .65, math.pi, 0, TAU, "graphite", 14, 32)
    for panel in range(4):
        angle = panel * math.pi / 2
        mesh.group = f"front_armor_petal_{panel}"
        armor_petal(mesh, .60, 1.47, angle, .738, "pearl")
        mesh.group = f"rear_armor_petal_{panel}"
        armor_petal(mesh, 1.62, 2.81, angle + .22, .734, "silver", 3.18)
    mesh.group = "equatorial_service_belt"
    patch(mesh, 3.115, 1.49, 1.59, 0, TAU, "steel", 1, 64)
    for i in range(24):
        patch(mesh, 3.125, 1.50, 1.565, i * TAU / 24 + .01, i * TAU / 24 + .09,
              "graphite", 1, 1)
    mesh.group = "rear_service_cap"
    patch(mesh, 3.13, 2.86, math.pi, 0, TAU, "steel", 3, 24)
    mesh.group = "optic_housing"
    lathe(mesh, [(1.72, -2.50), (1.86, -2.67), (1.83, -2.88), (1.72, -3.06), (1.49, -3.06), (1.38, -2.72)], "graphite", segments=48)
    lathe(mesh, [(1.83, -2.89), (1.74, -3.065), (1.70, -3.09), (1.65, -3.09)], "silver", segments=48)
    mesh.group = "segmented_cyan_ring"
    for i in range(64):
        a = i * TAU / 64
        lathe(mesh, [(1.655, -3.082), (1.50, -3.082)], "cyan", segments=1, span=(a + .011, a + TAU / 64 - .011))
    mesh.group = "recessed_red_optic"
    lathe(mesh, [(1.475, -3.04), (1.41, -2.87), (1.30, -2.80), (1.08, -2.80)], "rubber", segments=48)
    lathe(mesh, [(1.29, -2.82), (1.22, -2.84), (1.16, -2.82), (.99, -2.84)], "red_rim", segments=48)
    for i in range(48):
        a = i * TAU / 48
        lathe(mesh, [(1.20, -2.855), (1.055, -2.88)], "iris", segments=1, span=(a + .018, a + .065))
    lens = [(r, -2.99 + .28 * (r / 1.025) ** 2) for r in np.linspace(0, 1.025, 9)]
    start = len(mesh.faces)
    lathe(mesh, lens, "red_glass", segments=48, smooth=True)
    # Baked ceramic/glass shading also survives Minecraft's non-PBR item renderer.
    light = unit([-.26, .26, -1])
    for face_index in range(start, len(mesh.faces)):
        group, material, points, normals = mesh.faces[face_index]
        normal = unit(np.mean(normals, axis=0))
        diffuse = max(0, np.dot(normal, light))
        tone = min(15, round((.08 + .31 * diffuse + .63 * diffuse ** 75) * 15))
        mesh.faces[face_index] = (group, f"lens_{tone:02}", points, normals)
    mesh.group = "optic_retainer_tabs"
    for i in range(8):
        a = i * TAU / 8
        prism(mesh, rectangle(1.29, 1.49, -.075, .075, .025), -3.025, -2.90, "steel", angle=a)
    for side in (-1, 1):
        mesh.group = f"side_sensor_{side}"
        x = side * 2.68
        prism(mesh, rectangle(-.31, .31, -.52, .52, .13), -1.99, -1.24, "silver", "graphite", offset=(x, 0, 0))
        prism(mesh, rectangle(-.245, .245, -.43, .43, .09), -2.015, -1.99, "rubber", offset=(x, 0, 0))
        for y in (-.23, .23):
            lathe(mesh, [(.177, -2.025), (.147, -2.08), (.105, -2.08)], "steel", (x, y, 0), 16)
            lathe(mesh, [(.105, -2.081), (.085, -2.081)], "cyan", (x, y, 0), 16)
            lathe(mesh, [(0, -2.05), (.085, -2.05)], "rubber", (x, y, 0), 16)
    mesh.group = "armor_vents"
    for sign in (-1, 1):
        for i in range(3):
            theta = .91 + i * .060
            for phi in (math.pi / 2 - .38, math.pi / 2 + .30):
                patch(mesh, 3.245, theta, theta + .022,
                      phi + (math.pi if sign < 0 else 0), phi + .17 + (math.pi if sign < 0 else 0),
                      "graphite", 1, 3)
    mesh.group = "armor_fasteners"
    for i in range(8):
        phi = i * TAU / 8 + .14
        patch(mesh, 3.247, 1.32, 1.355, phi, phi + .035, "graphite", 1, 1)


def wings(mesh):
    for i in range(4):
        a = math.pi / 4 + i * math.pi / 2
        def pos(radial, lateral=0):
            return (radial * math.cos(a) - lateral * math.sin(a),
                    radial * math.sin(a) + lateral * math.cos(a), 0)
        mesh.group = f"wing_{i}_hinge"
        tangent = np.array([-math.sin(a), math.cos(a), 0])
        oriented_lathe(mesh, [(.30, -.64), (.38, -.56), (.38, .56), (.30, .64)], "steel", pos(3.15), tangent)
        for side in (-1, 1):
            oriented_lathe(mesh, [(0, side * .671), (.19, side * .671), (.25, side * .64)],
                           "silver", pos(3.15), tangent, flip=side > 0)
        prism(mesh, rectangle(3.12, 3.85, -.37, .37, .11), -.31, .22, "graphite", "steel", angle=a)
        mesh.group = f"wing_{i}_armored_frame"
        def skin(radial, lateral, lift=0):
            t = (radial - 3.55) / 4.63
            z = -.32 - .16 * math.sin(math.pi * t) + .30 * t ** 3 + lateral ** 2 * .28 + lift
            return np.array(pos(radial, lateral)) + [0, 0, z]
        def width(t):
            return .63 + .22 * math.sin(math.pi * t) - .06 * t
        for material, inset, lift in (("silver", 0, 0), ("rubber", .105, -.034)):
            grid = []
            for row in range(13):
                t = .025 + .95 * row / 12 if inset else row / 12
                grid.append([skin(3.55 + t * 4.63, (j / 6 * 2 - 1) * (width(t) - inset), lift) for j in range(7)])
            surface(mesh, grid, material)
            if not inset:
                boundary = grid[0] + [r[-1] for r in grid[1:]] + list(reversed(grid[-1][:-1])) + [r[0] for r in reversed(grid[1:-1])]
                for p, q in zip(boundary, boundary[1:] + boundary[:1]):
                    out = (p + q) / 2 - np.array(pos(5.865))
                    out[2] = 0
                    mesh.face([p, q, q + [0, 0, .22], p + [0, 0, .22]], "steel", out)
                surface(mesh, [[p + [0, 0, .22] for p in row] for row in grid], "graphite", back=True)
        for side in (-1, 1):
            rail = [[skin(3.55 + t * 4.63, side * (width(t) - .045) + delta, -.07)
                     for delta in (-.024, .024)] for t in np.linspace(.04, .96, 13)]
            surface(mesh, rail, "edge")
            # Recessed power bus is exposed between the nozzles and the rim.
            bus = [[skin(3.55 + t * 4.63, side * .49 + delta, -.08)
                    for delta in (-.018, .018)] for t in np.linspace(.14, .88, 10)]
            surface(mesh, bus, "copper")
        mesh.group = f"wing_{i}_emitter_bank"
        for j in range(5):
            radial = 4.08 + j * .85
            center = skin(radial, 0, -.035)
            lathe(mesh, [(.36, .01), (.36, -.14), (.30, -.20), (.30, -.47), (.26, -.53), (.21, -.53)], "steel", center, 20)
            lathe(mesh, [(.29, -.25), (.31, -.25), (.31, -.31), (.29, -.31)], "rubber", center, 20)
            lathe(mesh, [(.257, -.54), (.235, -.54)], "cyan", center, 20)
            lathe(mesh, [(0, -.39), (.18, -.39), (.21, -.48), (.21, -.535)], "graphite", center, 20)
        mesh.group = f"wing_{i}_fasteners"
        for radial in (3.78, 7.91):
            for side in (-1, 1):
                lathe(mesh, [(0, -.03), (.058, -.03), (.075, 0)], "steel", skin(radial, side * .48, -.04), 8)


def make_rf_model(item, display):
    return {
        "loader": "neoforge:composite", "ambientocclusion": False,
        "textures": {"particle": f"relics_addon:item/{item}"},
        "display": display,
        "children": {
            "body": {"loader": "neoforge:obj", "model": f"relics_addon:models/item/{item}.obj",
                     "automatic_culling": False, "shade_quads": True, "emissive_ambient": True,
                     "render_type": "minecraft:solid", "textures": {"particle": f"relics_addon:item/{item}"}},
            "plasma": {"render_type": "minecraft:translucent", "ambientocclusion": False,
                       "textures": {"fx": f"relics_addon:item/fx/{item}", "particle": f"relics_addon:item/{item}"},
                       "elements": [{"from": [0, 0, 1.1], "to": [16, 16, 14.9], "shade": False,
                                     "faces": {"north": {"texture": "#fx", "uv": [0, 0, 16, 16]},
                                               "south": {"texture": "#fx", "uv": [0, 0, 16, 16]}}}]},
        },
        "item_render_order": ["body", "plasma"],
    }


def build():
    MODELS.mkdir(parents=True, exist_ok=True)
    TEXTURES.mkdir(parents=True, exist_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    Image.new("RGBA", (16, 16), "white").save(TEXTURES / "rf_mesh_white.png")
    metadata = {}
    for item in ("rf_shield", "rf_drone"):
        mesh = Mesh()
        body(mesh)
        if item == "rf_drone":
            wings(mesh)
        metadata[item] = mesh.export(item, 1 if item == "rf_drone" else 1.72)
    (EVIDENCE / "mesh-audit.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="ascii")
    return metadata


if __name__ == "__main__":
    print(json.dumps(build(), indent=2))
