"""Original layered End-portal-style texture for the free Twins satellites."""
from pathlib import Path
import json
import numpy as np
from PIL import Image

root=Path(__file__).resolve().parents[1]/'src/main/resources/assets/relics_addon/textures/block/ship'
rng=np.random.default_rng(4301)
size=64; count=32
stars=[]
for layer in range(4):
    field=np.zeros((size,size,3),dtype=np.float32)
    for _ in range(65):
        x,y=rng.integers(0,size,2); intensity=rng.uniform(.3,1)
        field[y,x]=np.array([.48,.92,.84])*intensity/(1+layer*.25)
    stars.append(field)
frames=[]
y,x=np.mgrid[:size,:size]
for frame in range(count):
    phase=frame*2*np.pi/count
    fog=(np.sin(x*.15+phase)+np.cos(y*.18-phase)+2)/4
    image=np.stack([.009+fog*.008,.025+fog*.04,.026+fog*.038],axis=2)
    for layer,field in enumerate(stars):
        shift=round(frame*(layer+1)*size/count)
        image+=np.roll(field,(shift,-shift*(layer+1)),axis=(0,1))
    frames.append(np.clip(image*255,0,255).astype('uint8'))
root.mkdir(parents=True,exist_ok=True)
Image.fromarray(np.concatenate(frames,axis=0)).save(root/'end_portal.png')
(root/'end_portal.png.mcmeta').write_text(json.dumps({'animation':{'frametime':2,'interpolate':True}})+'\n')
print('End-portal-style atlas: 64x2048, 32 frames')
