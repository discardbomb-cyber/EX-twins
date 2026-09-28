"""Native closed hard-surface OBJ meshes for the three defender hives.

This intentionally owns only *_hive resources.  The existing drone and shield
generators stay the source of truth for their models and material palette.
"""

from __future__ import annotations

import itertools
import json
import math
from pathlib import Path

import numpy as np

from build_rf_meshes import Mesh, closed_radial_shell, lathe, patch, prism, spherical, unit

ROOT = Path(__file__).resolve().parents[1]
MODELS = ROOT / "src/main/resources/assets/relics_addon/models/item"
ANIMATED = MODELS / "animated"
EVIDENCE = ROOT / "reports/evidence/COMBAT-P08"
TAU = math.tau

# These are intentionally drawn from the existing native mesh palette rather
# than introducing texture atlases for the hives.
MATERIALS = {
    "silver": ((.47, .51, .55), 0), "edge": ((.92, .95, .98), 0),
    "graphite": ((.045, .055, .068), 0), "rubber": ((.019, .024, .032), 0),
    "steel": ((.24, .28, .33), 0), "red_rim": ((.34, .012, .025), 0),
    "red_glass": ((.78, .012, .035), .28), "cyan": ((.32, .77, 1.0), 1),
    "ceramic": ((.79, .81, .78), 0), "ceramic_shadow": ((.41, .48, .55), 0),
    "gold": ((.59, .40, .13), 0), "gold_edge": ((.91, .71, .30), 0),
    "navy": ((.024, .04, .09), 0), "mana_core": ((.045, .45, .83), .35),
    "mana_light": ((.28, .89, 1), .85), "obsidian": ((.075, .066, .11), 0),
    "armor": ((.16, .14, .22), 0), "armor_edge": ((.31, .28, .38), 0),
    "violet": ((.57, .10, .84), .55), "amethyst": ((.29, .05, .45), .22),
    "lilac": ((.83, .47, 1), .85),
}

DISPLAY = {
    "gui": {"rotation": [18, 208, 0], "scale": [.76, .76, .76]},
    "ground": {"translation": [0, 2, 0], "scale": [.45, .45, .45]},
    "fixed": {"scale": [.68, .68, .68]},
    "thirdperson_righthand": {"rotation": [15, -35, 10], "translation": [0, 1, 0], "scale": [.55, .55, .55]},
    "thirdperson_lefthand": {"rotation": [15, 35, -10], "translation": [0, 1, 0], "scale": [.55, .55, .55]},
    "firstperson_righthand": {"rotation": [10, -25, 0], "translation": [1, 2, 0], "scale": [.55, .55, .55]},
    "firstperson_lefthand": {"rotation": [10, 25, 0], "translation": [1, 2, 0], "scale": [.55, .55, .55]},
}


def rotate_faces(mesh, start, axis, angle, offset=(0, 0, 0)):
    axis = unit(axis)
    x, y, z = axis
    skew = np.array([[0, -z, y], [z, 0, -x], [-y, x, 0]])
    matrix = np.eye(3) * math.cos(angle) + (1 - math.cos(angle)) * np.outer(axis, axis) + skew * math.sin(angle)
    for index in range(start, len(mesh.faces)):
        group, material, points, normals = mesh.faces[index]
        mesh.faces[index] = (group, material, points @ matrix.T + offset, normals @ matrix.T)


def orb(mesh, radius, material, rows=8, columns=16):
    patch(mesh, radius, 0, math.pi, 0, TAU, material, rows, columns)


def ico_vertices_faces():
    phi = (1 + math.sqrt(5)) / 2
    vertices = np.array([(0, s, t * phi) for s in (-1, 1) for t in (-1, 1)]
                        + [(s, t * phi, 0) for s in (-1, 1) for t in (-1, 1)]
                        + [(t * phi, 0, s) for s in (-1, 1) for t in (-1, 1)], dtype=float)
    vertices /= np.linalg.norm(vertices[0])
    edge = min(np.linalg.norm(a - b) for a, b in itertools.combinations(vertices, 2))
    faces = []
    for ids in itertools.combinations(range(12), 3):
        triangle = vertices[list(ids)]
        if all(abs(np.linalg.norm(a - b) - edge) < 1e-6 for a, b in itertools.combinations(triangle, 2)):
            if np.dot(np.cross(triangle[1] - triangle[0], triangle[2] - triangle[0]), triangle.mean(axis=0)) < 0:
                ids = ids[::-1]
            faces.append(ids)
    return vertices, faces


def dodecahedron_faces():
    vertices, triangles = ico_vertices_faces()
    dual = np.array([unit(vertices[list(face)].mean(axis=0)) for face in triangles])
    faces = []
    for vertex_index, axis in enumerate(vertices):
        ids = [index for index, face in enumerate(triangles) if vertex_index in face]
        reference = unit(np.cross(axis, [0, 0, 1])) if abs(axis[2]) < .9 else np.array([1., 0., 0.])
        tangent = unit(np.cross(axis, reference))
        ids.sort(key=lambda index: math.atan2(np.dot(dual[index], tangent), np.dot(dual[index], reference)))
        polygon = dual[ids]
        if np.dot(np.cross(polygon[1] - polygon[0], polygon[2] - polygon[0]), polygon.mean(axis=0)) < 0:
            polygon = polygon[::-1]
        faces.append(polygon)
    return faces


def extruded_polygon(mesh, polygon, normal, depth, material, edge):
    """Emit a closed convex prism whose visible face has a distinct trim material."""
    normal = unit(normal)
    front = [point + normal * depth / 2 for point in polygon]
    back = [point - normal * depth / 2 for point in polygon]
    for index in range(1, len(polygon) - 1):
        mesh.face([front[0], front[index], front[index + 1]], material, normal)
        mesh.face([back[0], back[index + 1], back[index]], edge, -normal)
    center = np.mean(polygon, axis=0)
    for index in range(len(polygon)):
        following = (index + 1) % len(polygon)
        mesh.face([front[index], front[following], back[following], back[index]], edge,
                  (front[index] + front[following]) / 2 - center)


def ring_prism(mesh, outer, inner, normal, depth, material, edge):
    """Closed pentagonal frame, used for the Twins cage rather than painted lines."""
    normal = unit(normal)
    loops = [[point + normal * depth / 2 for point in loop] for loop in (outer, inner)]
    back_loops = [[point - normal * depth / 2 for point in loop] for loop in (outer, inner)]
    outer_front, inner_front = loops
    outer_back, inner_back = back_loops
    for index in range(len(outer)):
        next_index = (index + 1) % len(outer)
        mesh.face([outer_front[index], outer_front[next_index], inner_front[next_index], inner_front[index]], material, normal)
        mesh.face([outer_back[index], inner_back[index], inner_back[next_index], outer_back[next_index]], edge, -normal)
        mesh.face([outer_front[index], outer_back[index], outer_back[next_index], outer_front[next_index]], edge,
                  (outer[index] + outer[next_index]) / 2)
        mesh.face([inner_front[index], inner_front[next_index], inner_back[next_index], inner_back[index]], edge,
                  -(inner[index] + inner[next_index]) / 2)


def oriented_lathe(mesh, profile, material, center, axis, segments=20):
    start = len(mesh.faces)
    lathe(mesh, profile, material, segments=segments)
    z = unit(axis)
    x = unit(np.cross([0, 0, 1], z)) if abs(z[2]) < .98 else np.array([1., 0., 0.])
    y = unit(np.cross(z, x))
    matrix = np.column_stack((x, y, z))
    for index in range(start, len(mesh.faces)):
        group, mat, points, normals = mesh.faces[index]
        mesh.faces[index] = (group, mat, points @ matrix.T + center, normals @ matrix.T)


def framed_panel(mesh, center, normal, tangent, width, height, depth, face, edge, inset=None):
    """A closed chamfered hard-surface plate, constructed in the local tangent plane."""
    normal, tangent = unit(normal), unit(tangent)
    bitangent = unit(np.cross(normal, tangent))
    corners = [center + tangent * sx * width + bitangent * sy * height
               for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
    extruded_polygon(mesh, corners, normal, depth, face, edge)
    if inset:
        inset_corners = [center + normal * (depth * .57) + tangent * sx * width * .56 + bitangent * sy * height * .56
                         for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
        extruded_polygon(mesh, inset_corners, normal, depth * .18, inset, edge)


def radial_tangent(normal):
    return unit(np.cross(normal, [0, 0, 1])) if abs(normal[2]) < .88 else np.array([1., 0., 0.])


def socket(mesh, center, normal, radius, housing, rim, light):
    """Closed recessed docking face with a machined lip and a inset emitter."""
    segments = 24 if radius > .7 else 12 if radius > .15 else 6
    oriented_lathe(mesh, [(0, -.06), (radius * .90, -.06), (radius, .015),
                         (radius, .15), (radius * .80, .22), (radius * .65, .22),
                         (radius * .65, .07), (0, .07)], housing, center, normal, segments)
    oriented_lathe(mesh, [(radius * .67, .075), (radius * .76, .09),
                         (radius * .76, .14), (radius * .67, .15), (radius * .67, .075)],
                   rim, center, normal, segments)
    oriented_lathe(mesh, [(0, .076), (radius * .56, .076), (radius * .56, .102), (0, .102)],
                   light, center, normal, segments)


def surface_panel(mesh, radius, theta, phi, width, height, face, edge, inset):
    normal = unit(spherical(1, theta, phi))
    tangent = np.array([-math.sin(phi), math.cos(phi), 0.])
    center = normal * radius
    framed_panel(mesh, center, normal, tangent, width, height, .075, face, edge, inset)
    return center, normal, tangent


def rf_hive():
    mesh = Mesh(MATERIALS)
    mesh.group = "body"
    # A closed pressure hull remains behind every articulated armor seam.
    orb(mesh, 3.32, "graphite", 16, 32)
    patch(mesh, 3.36, 1.43, 1.71, 0, TAU, "steel", 2, 32)
    for index in range(24):
        patch(mesh, 3.38, 1.47, 1.67, index * TAU / 24 + .02, (index + .55) * TAU / 24, "graphite", 1, 1)
    # Recessed red optic and its mechanical collar face -Z, matching the RF concept.
    lathe(mesh, [(1.76, -2.78), (1.92, -2.91), (1.88, -3.18), (1.57, -3.30), (1.31, -3.13)], "steel", segments=40)
    lathe(mesh, [(1.58, -3.16), (1.46, -3.29), (1.25, -3.34), (1.02, -3.18)], "rubber", segments=40)
    mesh.group = "core"
    lathe(mesh, [(1.23, -3.20), (1.12, -3.35), (.86, -3.42), (0, -3.26)], "red_glass", segments=40, smooth=True)
    for angle in np.linspace(0, TAU, 16, endpoint=False):
        lathe(mesh, [(1.48, -3.305), (1.34, -3.305)], "red_rim", segments=1, span=(angle + .02, angle + .29))
    mesh.group = "fx"
    # Keep the transform scoped to this orb; its pole triangles make a face-count offset brittle.
    start = len(mesh.faces)
    orb(mesh, .62, "red_glass", 6, 12)
    rotate_faces(mesh, start, [1, 0, 0], 0, (0, 0, -3.44))
    mesh.group = "body"
    # Small docked spherical drone pods surround the service belt.
    for angle in np.linspace(0, TAU, 8, endpoint=False):
        position = np.array([3.25 * math.cos(angle), 3.25 * math.sin(angle), .30 * math.sin(angle * 2)])
        start = len(mesh.faces)
        orb(mesh, .34, "steel", 5, 10)
        rotate_faces(mesh, start, [1, 0, 0], 0, position)
        oriented_lathe(mesh, [(.19, -.37), (.19, -.46), (.12, -.51)], "cyan", position, unit(position), 12)
    # Four service pylons make the silhouette read as a deployed machine,
    # rather than a plain sphere with four smooth petals.
    for angle in np.linspace(0, TAU, 4, endpoint=False):
        normal = unit([math.cos(angle), math.sin(angle), -.18])
        tangent = radial_tangent(normal)
        framed_panel(mesh, normal * 3.02, normal, tangent, .42, .76, .18, "steel", "graphite", "cyan")
        for offset in (-.24, .24):
            lamp = normal * 3.18 + tangent * offset
            start = len(mesh.faces)
            orb(mesh, .105, "cyan", 4, 6)
            rotate_faces(mesh, start, [1, 0, 0], 0, lamp)
    for shell in range(4):
        mesh.group = f"shell_{shell}"
        angle = shell * TAU / 4
        grid = []
        for row in range(7):
            t = row / 6
            theta = .40 + t * 2.34
            width = .71 - .045 * abs(t - .5)
            grid.append([spherical(3.60 + .20 * math.sin(math.pi * t), theta, angle + width * (column / 6 * 2 - 1))
                         for column in range(7)])
        closed_radial_shell(mesh, grid, "silver", "graphite", "edge", .18)
        center, normal, tangent = surface_panel(mesh, 3.86, math.pi / 2, angle,
                .54, .68, "steel", "edge", "rubber")
        bitangent = unit(np.cross(normal, tangent))
        for vertical in (-.40, -.20, 0, .20, .40):
            framed_panel(mesh, center + bitangent * vertical + normal * .075, normal, tangent,
                         .38, .035, .04, "graphite", "steel")
        for sign in (-1, 1):
            theta = .93 if sign < 0 else 2.20
            normal = unit(spherical(1, theta, angle))
            socket(mesh, normal * 3.84, normal, .34, "graphite", "edge", "cyan")
            center, normal, tangent = surface_panel(mesh, 3.83, 1.29 if sign < 0 else 1.85,
                    angle + .43, .07, .24, "steel", "silver", "cyan")
        for phi in (angle - .54, angle + .54):
            for theta in (.76, 2.38):
                normal = unit(spherical(1, theta, phi))
                socket(mesh, normal * 3.76, normal, .085, "graphite", "steel", "silver")
    return mesh


def mana_hive():
    mesh = Mesh(MATERIALS)
    mesh.group = "body"
    # The glazed inner vessel closes the silhouette beneath the ivory articulated ribs.
    orb(mesh, 3.18, "ceramic_shadow", 16, 32)
    for axis, tilt in (([1, 0, 0], .0), ([0, 1, 0], .0), ([1, 1, 0], .0)):
        start = len(mesh.faces)
        lathe(mesh, [(3.22, -.075), (3.30, -.045), (3.30, .045), (3.22, .075)], "gold", segments=36)
        rotate_faces(mesh, start, axis, math.pi / 2 if axis[0] else 0)
    mesh.group = "core"
    orb(mesh, 2.20, "mana_core", 12, 24)
    for sign in (-1, 1):
        normal = np.array([0., 0., float(sign)])
        socket(mesh, normal * 3.22, normal, 1.02, "gold", "gold_edge", "mana_core")
        start = len(mesh.faces)
        orb(mesh, .48, "mana_light", 8, 16)
        rotate_faces(mesh, start, [1, 0, 0], 0, normal * 3.46)
    for axis in ([1, 0, 0], [0, 1, 0]):
        start = len(mesh.faces)
        lathe(mesh, [(2.29, -.06), (2.36, -.03), (2.36, .03), (2.29, .06)], "gold_edge", segments=36)
        rotate_faces(mesh, start, axis, math.pi / 2 if axis[0] else 0)
    mesh.group = "fx"
    # Six closed glow gems sit above the core instead of hiding inside it or z-fighting its surface.
    for angle in np.linspace(0, TAU, 6, endpoint=False):
        center = np.array([math.cos(angle), math.sin(angle), .22 * math.sin(angle * 2)]) * 2.48
        start = len(mesh.faces)
        orb(mesh, .22, "mana_light", 4, 8)
        rotate_faces(mesh, start, [1, 0, 0], 0, center)
    mesh.group = "body"
    for angle in np.linspace(0, TAU, 6, endpoint=False):
        direction = np.array([math.cos(angle), math.sin(angle), 0])
        center = direction * 3.14
        oriented_lathe(mesh, [(.43, -.22), (.50, -.11), (.50, .11), (.43, .22)], "gold", center, direction, 16)
        oriented_lathe(mesh, [(0, -.24), (.31, -.22), (.34, -.10)], "mana_light", center + direction * .03, direction, 16)
        # A narrow gold spine connects every arc to the suspended crystal.
        for sign in (-1, 1):
            spine = center + np.array([0, 0, sign * .67])
            oriented_lathe(mesh, [(.055, -.74), (.055, .74)], "gold_edge", spine, direction, 8)
    for shell in range(6):
        mesh.group = f"shell_{shell}"
        angle = shell * TAU / 6
        grid = []
        for row in range(9):
            t = row / 8
            theta = .30 + t * 2.54
            width = .43 + .055 * math.sin(math.pi * t)
            grid.append([spherical(3.48 + .13 * math.sin(math.pi * t), theta, angle + width * (column / 7 * 2 - 1))
                         for column in range(8)])
        closed_radial_shell(mesh, grid, "ceramic", "ceramic_shadow", "gold_edge", .18)
        # Tangent details sit above the true surface, never at the chord midpoint under the skin.
        normal = unit(np.mean(grid[4], axis=0))
        center = normal * 3.70
        tangent = radial_tangent(normal)
        bitangent = unit(np.cross(normal, tangent))
        framed_panel(mesh, center, normal, tangent, .34, .56, .075, "gold", "gold_edge", "navy")
        arch = [center + tangent * math.cos(a) * .145 + bitangent * (.13 + math.sin(a) * .20) + normal * .09
                for a in np.linspace(math.pi, 0, 7)]
        arch += [center + tangent * .13 + bitangent * -.23 + normal * .09,
                 center + tangent * -.13 + bitangent * -.23 + normal * .09]
        extruded_polygon(mesh, arch, normal, .035, "gold", "gold_edge")
        diamond = [center + tangent * .085 + normal * .13, center + bitangent * .13 + normal * .13,
                   center - tangent * .085 + normal * .13, center - bitangent * .13 + normal * .13]
        extruded_polygon(mesh, diamond, normal, .052, "mana_light", "gold_edge")
        for theta in (.85, 2.29):
            outward = unit(spherical(1, theta, angle))
            socket(mesh, outward * 3.65, outward, .28, "gold", "gold_edge", "mana_core")
        for sign in (-1, 1):
            surface_panel(mesh, 3.70, math.pi / 2, angle + sign * .33,
                          .025, .34, "gold_edge", "gold", "mana_light")
    return mesh


def twins_hive():
    mesh = Mesh(MATERIALS)
    pentagons = dodecahedron_faces()
    mesh.group = "body"
    # A real inner vault fills the gaps while the exterior facets rotate independently.
    orb(mesh, 2.72, "obsidian", 12, 24)
    for polygon in pentagons:
        outer = polygon * 3.02
        center = outer.mean(axis=0)
        extruded_polygon(mesh, outer, center, .12, "obsidian", "armor")
    mesh.group = "core"
    # A real central crystal must remain legible at inventory scale; the previous nested plates
    # looked like an empty dark cage once Minecraft's lighting flattened the small details.
    orb(mesh, 1.18, "amethyst", 8, 16)
    core_vertices, core_faces = ico_vertices_faces()
    for face_index, face in enumerate(core_faces):
        crystal = core_vertices[list(face)] * 1.32
        mesh.face(crystal, "lilac" if face_index % 3 == 0 else "violet", crystal.mean(axis=0))
    for polygon in pentagons:
        normal = unit(polygon.mean(axis=0))
        inset = polygon * 2.04
        extruded_polygon(mesh, inset, normal, .15, "amethyst", "obsidian")
        center = inset.mean(axis=0)
        # A second faceted vault is deliberately spaced away from the plate;
        # it produces depth through silhouette, not coincident transparent skins.
        smaller = center + (inset - center) * .56 + normal * .17
        extruded_polygon(mesh, smaller, normal, .09, "armor", "amethyst")
    mesh.group = "fx"
    for polygon in pentagons:
        outer = polygon * 3.28
        center = outer.mean(axis=0)
        inner = center + (outer - center) * .72
        # Exterior luminous cage inlays sit clear of the amethyst core below them.
        ring_prism(mesh, outer, inner, center, .055, "lilac", "violet")
        for vertex in inner:
            start = len(mesh.faces)
            orb(mesh, .065, "lilac", 3, 5)
            rotate_faces(mesh, start, [1, 0, 0], 0, vertex + unit(vertex) * .06)
    mesh.group = "body"
    # Six faceted drone pods live within the cage openings.
    for direction in (np.array([1., 0, 0]), np.array([-1., 0, 0]), np.array([0, 1., 0]), np.array([0, -1., 0]), np.array([0, 0, 1.]), np.array([0, 0, -1.])):
        center = direction * 2.66
        vertices, faces = ico_vertices_faces()
        for face in faces:
            mesh.face(vertices[list(face)] * .34 + center, "armor" if len(mesh.faces) % 2 else "violet", center)
    normals = []
    for shell, polygon in enumerate(pentagons):
        mesh.group = f"shell_{shell}"
        outer = polygon * 4.15
        normal = unit(outer.mean(axis=0))
        center = outer.mean(axis=0)
        plate = center + (outer - center) * .91
        extruded_polygon(mesh, plate, normal, .24, "armor", "obsidian")
        inset_plate = center + (plate - center) * .72 + normal * .16
        extruded_polygon(mesh, inset_plate, normal, .07, "obsidian", "armor_edge")
        # Thin physical circuitwork preserves a mostly-black armor plate at inventory scale.
        circuit_outer = center + (plate - center) * .62 + normal * .205
        circuit_inner = center + (plate - center) * .49 + normal * .205
        ring_prism(mesh, circuit_outer, circuit_inner, normal, .055, "violet", "armor_edge")
        for branch in (0, 2, 4):
            start = center + (plate[branch] - center) * .16 + normal * .23
            end = center + (plate[branch] - center) * .58 + normal * .23
            sideways = unit(np.cross(normal, end - start)) * .028
            extruded_polygon(mesh, [start + sideways, end + sideways, end - sideways, start - sideways],
                             normal, .045, "violet", "armor_edge")
        socket(mesh, center + normal * .26, normal, .36, "obsidian", "armor_edge", "violet")
        for corner in range(5):
            mount = center + (plate[corner] - center) * .78 + normal * .17
            framed_panel(mesh, mount, normal, radial_tangent(normal), .065, .11, .055,
                         "armor_edge", "obsidian", "lilac")
        # An asymmetric vertex gem makes each rotating plate look intentional.
        gem_center = center + (plate[1] - center) * .72 + normal * .27
        start = len(mesh.faces)
        orb(mesh, .105, "lilac", 4, 6)
        rotate_faces(mesh, start, [1, 0, 0], 0, gem_center)
        normals.append(normal.tolist())
    return mesh, normals


def part(item, group, translucent=False):
    visibility = {name: name == group for name in groups_for(item)}
    return {
        "loader": "neoforge:obj", "model": f"relics_addon:models/item/{item}.obj",
        "automatic_culling": False, "shade_quads": True, "emissive_ambient": True,
        "render_type": "minecraft:translucent" if translucent else "minecraft:solid",
        "textures": {"particle": f"relics_addon:item/{item.removesuffix('_hive')}_drone"}, "visibility": visibility,
    }


def groups_for(item):
    count = {"rf_hive": 4, "mana_hive": 6, "twins_hive": 12}[item]
    return ["body", "core", "fx"] + [f"shell_{index}" for index in range(count)]


def closed_groups(mesh):
    """Report manifold-by-position groups and identify the closed structural volume."""
    report = {}
    for group in sorted({face[0] for face in mesh.faces}):
        edges = {}
        for name, _, points, _ in mesh.faces:
            if name != group:
                continue
            keys = [tuple(np.round(point, 6)) for point in points]
            for a, b in zip(keys, keys[1:] + keys[:1]):
                key = tuple(sorted((a, b)))
                edges[key] = edges.get(key, 0) + 1
        report[group] = {"boundary_edges": sum(count == 1 for count in edges.values()), "non_manifold_edges": sum(count > 2 for count in edges.values())}
    return report


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def build_item(item, maker, scale):
    result = maker()
    mesh, normals = result if isinstance(result, tuple) else (result, None)
    metadata = mesh.export(item, scale)
    group_closedness = closed_groups(mesh)
    structural_groups = ["fx"] + [name for name in group_closedness if name.startswith("shell_")]
    metadata["closedness"] = {
        "groups": group_closedness,
        "structural_groups": structural_groups,
        "passed": all(group_closedness[name]["boundary_edges"] == 0 and group_closedness[name]["non_manifold_edges"] == 0
                      for name in structural_groups),
        "note": "Body and core include layered recess/trim geometry; animated shells and luminous volume are manifold closed solids.",
    }
    if not metadata["closedness"]["passed"]:
        raise ValueError(f"{item} has an open animated structural volume")
    metadata["face_budget"] = {"limit": 8000, "passed": metadata["faces"] <= 8000}
    if not metadata["face_budget"]["passed"]:
        raise ValueError(f"{item} exceeds 8000 faces")
    write_json(MODELS / f"{item}.json", {"parent": "minecraft:builtin/entity", "gui_light": "side",
                                          "textures": {"particle": f"relics_addon:item/{item.removesuffix('_hive')}_drone"}, "display": DISPLAY})
    for group in groups_for(item):
        write_json(ANIMATED / f"{item}_{group}.json", part(item, group, group == "fx"))
    if normals is not None:
        metadata["shell_pivots"] = [{"shell": index, "pivot": [8.0, 8.0, 8.0], "normal": normal}
                                    for index, normal in enumerate(normals)]
    return metadata


def build():
    MODELS.mkdir(parents=True, exist_ok=True)
    ANIMATED.mkdir(parents=True, exist_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    audit = {
        "rf_hive": build_item("rf_hive", rf_hive, 1.28),
        "mana_hive": build_item("mana_hive", mana_hive, 1.22),
        "twins_hive": build_item("twins_hive", twins_hive, 1.20),
    }
    (EVIDENCE / "hive-mesh-audit.json").write_text(json.dumps(audit, indent=2) + "\n", encoding="utf-8")
    return audit


if __name__ == "__main__":
    print(json.dumps(build(), indent=2))
