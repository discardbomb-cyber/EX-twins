"""Offline animated stands from the real OBJ groups, without launching Minecraft.

Software lighting/bloom is a presentation effect, not a Photon/client verification.
"""
import argparse, json, math, os, subprocess
from pathlib import Path
import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--out', required=True)
parser.add_argument('--frames', type=int, default=48)
parser.add_argument('--active-only', action='store_true')
parser.add_argument('--portal-transitions', action='store_true')
parser.add_argument('--variants-only', action='store_true')
parser.add_argument('--only', choices=['rf','mana','twins'])
args = parser.parse_args()
dest = Path(args.out); dest.mkdir(parents=True, exist_ok=True)
names = [('rf', 'RF', '#52c4ff'), ('mana', 'Mana', '#48e2ff'), ('twins', 'Ex-Twins', '#d478ff')]
if args.portal_transitions:
    for mode in ['opening','closing']:
        folder=dest/'twins'/mode; folder.mkdir(parents=True,exist_ok=True)
        for i in range(args.frames):
            phase=i/(args.frames-1)
            ramp=phase if mode=='opening' else 1-phase
            target=folder/f'{i:03}.png'
            subprocess.run(['node',str(ROOT/'tools/preview_obj.mjs'),'twins_ship_shield_generator',
                '--pose','on','--time',str(phase*6),'--open',str(ramp),
                '--fixed-camera','--view','corner','--background','dark','--size','480','--out',str(target)],
                cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
            if i%12==0: print(f'Twins {mode}: {i}/{args.frames}',flush=True)
    (dest/'index.html').write_text((ROOT/'tools/ship_stands.html').read_text(encoding='utf-8').replace('COUNT',str(args.frames)),encoding='utf-8')
    print('Twins: dedicated six-second entry/exit ready',flush=True)
    raise SystemExit(0)
for key, label, colour in names:
    if args.only and key != args.only: continue
    folder = dest / key; folder.mkdir(exist_ok=True)
    subprocess.run(['node', str(ROOT/'tools/preview_obj.mjs'), f'{key}_ship_shield_generator',
        '--pose', 'rest', '--time', '0', '--open', '0', '--fixed-camera',
        '--view', 'corner', '--background', 'dark', '--size', '480', '--out', str(folder/'inactive.png')],
        cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    if args.variants_only: continue
    base_folder=folder
    if args.active_only: folder=folder/"active"; folder.mkdir(exist_ok=True)
    frames = []
    for i in range(args.frames):
        phase = i / (args.frames if args.active_only else args.frames-1)
        ramp = min(1, phase/.22, (1-phase)/.22)
        power = 1 if args.active_only else ramp*ramp*(3-2*ramp)
        target = folder / f'{i:03}.png'
        subprocess.run(['node', str(ROOT/'tools/preview_obj.mjs'), f'{key}_ship_shield_generator',
            '--texture-frame', str(int(phase*32)), '--pose', 'on', '--time', str(phase*12), '--open', str(power), '--fixed-camera',
            '--view', 'corner', '--background', 'dark', '--size', '480', '--out', str(target)],
            cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
        image = Image.open(target).convert('RGB')
        rgb = np.asarray(image).astype(np.float32)
        # Only strongly coloured bright pixels contribute to the branch-coloured bloom.
        mask = (rgb.max(axis=2)>145)&((rgb.max(axis=2)-rgb.min(axis=2))>65)
        emissive = Image.fromarray(np.where(mask[:,:,None],rgb,0).astype('uint8'))
        glow = np.asarray(emissive.filter(ImageFilter.GaussianBlur(7))).astype(np.float32)
        tight = np.asarray(emissive.filter(ImageFilter.GaussianBlur(2))).astype(np.float32)
        image = Image.fromarray(np.clip(rgb+.65*glow+.30*tight,0,255).astype('uint8'))
        image.save(target)
        frames.append(image)
        if i%12==0: print(f'{label}: {i}/{args.frames}', flush=True)
    frames[0].save(dest/f'{key}{"-active" if args.active_only else ""}.gif',save_all=True,append_images=frames[1:],duration=125,loop=0,disposal=2)
    frames[args.frames//2].save(dest/f'{key}.png')
    print(f'{label}: ready', flush=True)
html=(ROOT/'tools/ship_stands.html').read_text(encoding='utf-8').replace('COUNT',str(args.frames))
(dest/'index.html').write_text(html,encoding='utf-8')
print(dest/'index.html',flush=True)
