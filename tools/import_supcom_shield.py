"""Read an installed Forged Alliance SCM v5 mesh into OBJ/JSON, preserving UVs and bone IDs.

SCM layout reference: Oygron/SupCom_Import_Export_Blender, supcom-importer.py.
No Blender or game installation is modified. Output is an explicitly supplied local directory.
"""
import argparse
import hashlib
import io
import json
import struct
import zipfile
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('--game-dir', required=True)
parser.add_argument('--unit', required=True)
parser.add_argument('--out-dir', required=True)
args = parser.parse_args()
unit = args.unit.upper()
out = Path(args.out_dir)
out.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(Path(args.game_dir) / 'gamedata/units.scd') as archive:
    names = {name.lower(): name for name in archive.namelist()}
    mesh_path = f'units/{unit}/{unit}_lod0.scm'.lower()
    data = archive.read(names[mesh_path])
    albedo = archive.read(names[f'units/{unit}/{unit}_albedo.dds'.lower()])
header = struct.unpack_from('<4s11I', data)
magic, version, bone_offset, bone_count, vertex_offset, _, vertex_count, index_offset, index_count, *_ = header
if magic != b'MODL' or version != 5 or index_count % 3:
    raise ValueError('Expected SCM v5 with triangle indices')
bones = []
for index in range(bone_count):
    row = struct.unpack_from('<16f3f4f4i', data, bone_offset + index * 108)
    name_offset = row[23]
    end = data.index(b'\0', name_offset)
    bones.append({'name': data[name_offset:end].decode('ascii'), 'parent': row[24], 'inverseBind': row[:16], 'position': row[16:19], 'quaternion': row[19:23]})
vertices = [struct.unpack_from('<16f4B', data, vertex_offset + i * 68) for i in range(vertex_count)]
indices = struct.unpack_from(f'<{index_count}H', data, index_offset)
if max(indices) >= vertex_count:
    raise ValueError('Face references invalid vertex')
low = [min(v[j] for v in vertices) for j in range(3)]
high = [max(v[j] for v in vertices) for j in range(3)]
scale = 1 / max(high[0]-low[0], high[2]-low[2])
cx, cz = (low[0]+high[0])/2, (low[2]+high[2])/2
positions = [[.5+(v[0]-cx)*scale, (v[1]-low[1])*scale, .5+(v[2]-cz)*scale] for v in vertices]
normals = [list(v[6:9]) for v in vertices]
uvs = [[v[12], v[13]] for v in vertices]
faces = [list(indices[i:i+3]) for i in range(0,index_count,3)]
result = {'unit':unit, 'sourceSha256':hashlib.sha256(data).hexdigest(), 'sourceMesh':mesh_path,
          'scale':scale, 'low':low, 'centreXZ':[cx,cz], 'bones':bones, 'positions':positions,
          'normals':normals, 'uvs':uvs, 'vertexBones':[v[16] for v in vertices], 'faces':faces}
stem = unit.lower()
(out / f'{stem}.json').write_text(json.dumps(result), encoding='utf-8')
image = Image.open(io.BytesIO(albedo)).convert('RGB')
image.save(out / f'{stem}.png')
lines = [f'mtllib {stem}.mtl', 'g body', 'usemtl original']
lines += ['v '+' '.join(f'{x:.8f}' for x in p) for p in positions]
lines += ['vt '+' '.join(f'{x:.8f}' for x in uv) for uv in uvs]
lines += ['vn '+' '.join(f'{x:.8f}' for x in n) for n in normals]
lines += ['f '+' '.join(f'{i+1}/{i+1}/{i+1}' for i in face) for face in faces]
(out / f'{stem}.obj').write_text('\n'.join(lines)+'\n',encoding='utf-8')
(out / f'{stem}.mtl').write_text(f'newmtl original\nKd 1 1 1\nKa 0 0 0\nmap_Kd {(out / (stem+".png")).as_posix()}\n',encoding='utf-8')
print(f'{unit}: {vertex_count} vertices, {len(faces)} original triangles, {image.size} albedo; bones '+', '.join(b['name'] for b in bones))
