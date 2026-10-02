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
args = parser.parse_args()
dest = Path(args.out); dest.mkdir(parents=True, exist_ok=True)
names = [('rf', 'RF', '#52c4ff'), ('mana', 'Mana', '#48e2ff'), ('twins', 'Ex-Twins', '#d478ff')]
for key, label, colour in names:
    folder = dest / key; folder.mkdir(exist_ok=True)
    frames = []
    for i in range(args.frames):
        phase = i / args.frames
        power = min(1, phase/.18, (1-phase)/.18)
        target = folder / f'{i:03}.png'
        subprocess.run(['node', str(ROOT/'tools/preview_obj.mjs'), f'{key}_ship_shield_generator',
            '--pose', 'on', '--time', str(phase*12), '--open', str(power), '--fixed-camera',
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
    frames[0].save(dest/f'{key}.gif',save_all=True,append_images=frames[1:],duration=125,loop=0,disposal=2)
    frames[args.frames//2].save(dest/f'{key}.png')
    print(f'{label}: ready', flush=True)
cards=''.join(f'<article style="--accent:{colour}"><h2>{label}</h2><canvas width="480" height="480" data-branch="{key}"></canvas><p>{desc}</p></article>' for (key,label,colour),desc in zip(names,[
    'Индукционное кольцо, орбита радиаторов, импульсы силовых шин.',
    'Последовательное раскрытие лепестков, вращение ядра, мягкая левитация.',
    'Орбита сфер, вращение центральной системы, нарастание энергетических нитей.']))
html='''<!doctype html><html lang="ru"><meta charset="utf-8"><title>Корабельные щиты — стенды</title>
<style>body{margin:0;background:#10141b;color:#e2e8ef;font:16px system-ui;padding:28px}h1{font-size:25px}header p{color:#9ba9bc}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:18px}article{border:1px solid #303949;border-top:3px solid var(--accent);border-radius:12px;padding:18px;background:#161a20}h2{color:var(--accent);margin:0}canvas{width:100%;height:auto}article p{font-size:14px;min-height:40px;color:#b4bdca}.controls{display:flex;gap:20px;align-items:center;flex-wrap:wrap;padding:20px 0}button,select{padding:9px;background:#283344;border:1px solid #59677b;color:white;border-radius:6px}input{accent-color:#7fd2ff}#seek{width:260px}footer{color:#8594a9;font-size:13px}</style>
<header><h1>Корабельные щиты · анимационные стенды</h1><p>Цикл: включение → работа → выключение. Реальная геометрия моделей, фиксированная камера.</p></header>
<div class="controls"><button id="play">Пауза</button><label>Скорость <select id="speed"><option value=".5">½×</option><option selected value="1">1×</option><option value="2">2×</option></select></label><label>Кадр <input id="seek" type="range" min="0" max="47" value="0"></label><label><input id="fx" type="checkbox" checked> Эффект поля</label><span id="state"></span></div><main>CARDS</main><footer>Автономный стенд без Minecraft. Свечение и контуры поля иллюстрируют эффекты; работа Photon и игровой рендер здесь не проверяются.</footer>
<script>const count=COUNT,canvases=[...document.querySelectorAll('canvas')],images={};for(const c of canvases){images[c.dataset.branch]=Array.from({length:count},(_,i)=>{const im=new Image();im.src=c.dataset.branch+'/'+String(i).padStart(3,'0')+'.png';return im})}let playing=true,frame=0,prev=0;const play=document.querySelector('#play'),seek=document.querySelector('#seek');seek.max=count-1;play.onclick=()=>{playing=!playing;play.textContent=playing?'Пауза':'Продолжить'};seek.oninput=()=>{frame=+seek.value;playing=false;play.textContent='Продолжить'};function draw(now){const delta=prev?(now-prev)/1000:0;prev=now;if(playing)frame=(frame+delta*8*+document.querySelector('#speed').value)%count;const i=Math.floor(frame),phase=i/count,power=Math.min(1,phase/.18,(1-phase)/.18);seek.value=i;document.querySelector('#state').textContent=(phase<.18?'Включение':phase>.82?'Выключение':'Работа')+' · '+Math.round(power*100)+'%';for(const c of canvases){const x=c.getContext('2d'),im=images[c.dataset.branch][i];x.fillStyle='#161a20';x.fillRect(0,0,480,480);if(im.complete&&im.naturalWidth)x.drawImage(im,0,0);if(document.querySelector('#fx').checked&&power>.01){x.save();x.globalCompositeOperation='screen';x.strokeStyle=getComputedStyle(c.parentElement).getPropertyValue('--accent');x.globalAlpha=.12*power*(.8+.2*Math.sin(frame*.7));x.lineWidth=1;for(let k=0;k<3;k++){x.beginPath();x.ellipse(240,245,145+k*8,180+k*5,0,0,Math.PI*2);x.stroke()}x.restore()}}requestAnimationFrame(draw)}requestAnimationFrame(draw);</script></html>'''.replace('CARDS',cards).replace('COUNT',str(args.frames))
(dest/'index.html').write_text(html,encoding='utf-8')
print(dest/'index.html',flush=True)
