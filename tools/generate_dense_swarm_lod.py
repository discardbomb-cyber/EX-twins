"""Export only three closed, <=100-face drone meshes for dense/distant swarms.

No full-detail model generators are invoked. Each OBJ is a single baked hull,
using the existing white material texture and no translucent render pass.
"""

from __future__ import annotations

import itertools
import json
import math
from collections import defaultdict

import numpy as np

from build_rf_meshes import MODELS, Mesh, closed_radial_shell, patch, spherical, unit


MATERIALS = {
    "silver": ((.59, .64, .69), 0),
    "graphite": ((.035, .042, .055), 0),
    "steel": ((.25, .29, .34), 0),
    "red_optic": ((.86, .015, .035), .4),
    "mana_core": ((.045, .46, .88), .3),
    "porcelain": ((.88, .90, .87), 0),
    "porcelain_back": ((.40, .49, .59), 0),
    "gold": ((.82, .61, .22), 0),
    "obsidian": ((.072, .050, .11), 0),
    "armor": ((.16, .105, .23), 0),
    "violet": ((.58, .13, .88), .45),
}


def icosahedron():
    phi = (1 + math.sqrt(5)) / 2
    vertices = np.array([(0, a, b * phi) for a in (-1, 1) for b in (-1, 1)]
                        + [(a, b * phi, 0) for a in (-1, 1) for b in (-1, 1)]
                        + [(b * phi, 0, a) for a in (-1, 1) for b in (-1, 1)], dtype=float)
    vertices /= np.linalg.norm(vertices[0])
    edge = min(np.linalg.norm(a - b) for a, b in itertools.combinations(vertices, 2))
    faces = [vertices[list(ids)] for ids in itertools.combinations(range(12), 3)
             if all(abs(np.linalg.norm(vertices[a] - vertices[b]) - edge) < 1e-7
                    for a, b in itertools.combinations(ids, 2))]
    return faces


def new_mesh():
    mesh = Mesh(MATERIALS)
    mesh.group = "hull"
    return mesh


def rf_drone_dense():
    mesh = new_mesh()
    patch(mesh, 3.04, 0, math.pi, 0, math.tau, "silver", rows=4, columns=8)
    for wing in range(4):
        angle = math.pi / 4 + wing * math.pi / 2
        co, si = math.cos(angle), math.sin(angle)
        outline = [(2.80, -.30), (6.65, -.49), (6.65, .37), (3.15, .53)]
        front = [np.array([x * co - y * si, x * si + y * co, -.22]) for x, y in outline]
        back = [point + [0, 0, .40] for point in front]
        mesh.face(front, "graphite", [0, 0, -1])
        mesh.face(back, "graphite", [0, 0, 1])
        center = np.mean(front, axis=0)
        for i in range(4):
            j = (i + 1) % 4
            mesh.face([front[i], front[j], back[j], back[i]], "steel", (front[i] + front[j]) / 2 - center)
    # A closed, flattened octahedron is visible from front and oblique angles.
    center = np.array([0., 0., -3.02])
    rim = [center + point for point in ([.92, 0, 0], [0, .92, 0], [-.92, 0, 0], [0, -.92, 0])]
    for tip in (center + [0, 0, -.28], center + [0, 0, .20]):
        for i in range(4):
            face = [tip, rim[i], rim[(i + 1) % 4]]
            mesh.face(face, "red_optic", np.mean(face, axis=0) - center)
    return mesh


def mana_drone_dense():
    mesh = new_mesh()
    for triangle in icosahedron():
        mesh.face(triangle * 2.4, "mana_core", triangle.mean(axis=0))
    for fin in range(6):
        angle = fin * math.tau / 6
        # Two longitudinal sections retain the bowed petal outline, including
        # its back and gold edge walls, in ten faces per fin.
        grid = [[spherical(radius, theta, angle + side * .30) for side in (-1, 1)]
                for theta, radius in ((.43, 4.15), (1.57, 4.40), (2.71, 4.15))]
        closed_radial_shell(mesh, grid, "porcelain", "porcelain_back", "gold", .20)
    return mesh


def twins_drone_dense():
    mesh = new_mesh()
    for index, triangle in enumerate(icosahedron()):
        outer = triangle * 4.25
        center = outer.mean(axis=0)
        inset = center + (outer - center) * .85 - unit(center) * .10
        mesh.face(inset, "obsidian" if index % 3 else "armor", center)
        for i in range(3):
            j = (i + 1) % 3
            mesh.face([outer[i], outer[j], inset[j], inset[i]], "violet", center)
    return mesh


def audit_obj(path):
    """Audit the rounded exported geometry, not just its source arrays."""
    vertices, normals, faces = [], [], []
    for line in path.read_text(encoding="ascii").splitlines():
        values = line.split()
        if not values:
            continue
        if values[0] == "v":
            vertices.append(np.array([float(value) for value in values[1:]]))
        elif values[0] == "vn":
            normals.append(np.array([float(value) for value in values[1:]]))
        elif values[0] == "f":
            faces.append([(int(part.split("/")[0]) - 1, int(part.split("/")[2]) - 1) for part in values[1:]])
    if not 1 <= len(faces) <= 100:
        raise ValueError(f"{path.name}: exceeds the 100-face budget")
    if not np.isfinite(np.array(vertices)).all() or not np.isfinite(np.array(normals)).all():
        raise ValueError(f"{path.name}: non-finite coordinate or normal")
    if any(abs(np.linalg.norm(normal) - 1) > 2e-6 for normal in normals):
        raise ValueError(f"{path.name}: non-unit normal")
    edges, volumes = defaultdict(list), []
    for face_index, face in enumerate(faces):
        points = np.array([vertices[v] for v, _ in face])
        outward = unit(np.cross(points[1] - points[0], points[2] - points[0]))
        if len(face) not in (3, 4) or any(np.dot(outward, normals[n]) <= 0 for _, n in face):
            raise ValueError(f"{path.name}: reversed face/normal {face_index}")
        if np.max(np.abs((points - points[0]) @ outward)) > 3e-7:
            raise ValueError(f"{path.name}: non-planar quad {face_index}")
        keys = [tuple(point) for point in points]
        for a, b in zip(keys, keys[1:] + keys[:1]):
            edges[tuple(sorted((a, b)))].append((face_index, a, b))
        volumes.append(sum(np.dot(points[0], np.cross(points[i], points[i + 1])) / 6
                           for i in range(1, len(points) - 1)))
    adjacent = [set() for _ in faces]
    for uses in edges.values():
        if len(uses) != 2 or uses[0][1:] != uses[1][1:][::-1]:
            raise ValueError(f"{path.name}: open/non-manifold edge or inconsistent winding")
        a, b = uses[0][0], uses[1][0]
        adjacent[a].add(b)
        adjacent[b].add(a)
    remaining, components = set(range(len(faces))), 0
    while remaining:
        pending, component = [remaining.pop()], set()
        while pending:
            face = pending.pop()
            if face in component:
                continue
            component.add(face)
            pending.extend(adjacent[face] - component)
        remaining.difference_update(component)
        if sum(volumes[face] for face in component) <= 1e-9:
            raise ValueError(f"{path.name}: reversed or zero-volume solid")
        components += 1
    return {"faces": len(faces), "closed_solids": components, "unit_outward_normals": True,
            "planar_faces": True, "boundary_edges": 0, "non_manifold_edges": 0}


def main():
    report = {}
    for name, make, scale in (("rf_drone_dense", rf_drone_dense, 1.0),
                              ("mana_drone_dense", mana_drone_dense, 1.18),
                              ("twins_drone_dense", twins_drone_dense, 1.35)):
        mesh = make()
        metadata = mesh.export(name, scale)
        report[name] = {**metadata, **audit_obj(MODELS / f"{name}.obj")}
        model = {"loader": "neoforge:obj", "model": f"relics_addon:models/item/{name}.obj",
                 "automatic_culling": False, "shade_quads": True, "emissive_ambient": True,
                 "render_type": "minecraft:solid", "textures": {"particle": f"relics_addon:item/{name.removesuffix('_dense')}"},
                 "visibility": {"hull": True}}
        (MODELS / "animated" / f"{name}.json").write_text(json.dumps(model, indent=2) + "\n", encoding="ascii")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
