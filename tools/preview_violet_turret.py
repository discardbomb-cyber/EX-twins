import json, math, os, base64, io
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

model=json.load(open('artifacts/blockbench/violet_turret/violet_javelin_turret.bbmodel',encoding='utf-8'))
palette=['17131e','28222f','655a70','392449','582b78','793e9f','a064cf','bb8cd7']
texture=np.array(Image.open(io.BytesIO(base64.b64decode(model['textures'][0]['source'].split(',')[1]))).convert('RGB'))
az=math.radians(216); el=math.radians(30)
view=np.array([math.sin(az)*math.cos(el),math.sin(el),math.cos(az)*math.cos(el)])
light=np.array([-.55,.8,-.45]);light/=np.linalg.norm(light)
halfway=(light+view);halfway/=np.linalg.norm(halfway)
parents={}
def collect(gs,launcher=None):
 for g in gs:
  if isinstance(g,str):parents[g]=launcher
  else:collect(g['children'],g['origin'][0] if g['name'].startswith('launcher_') else launcher)
collect(model['outliner'])
def project(p):
 x,y,z=p
 u=math.cos(az)*x-math.sin(az)*z
 depth=math.sin(az)*x+math.cos(az)*z
 v=math.cos(el)*y-math.sin(el)*depth
 d=math.cos(el)*depth+math.sin(el)*y
 return (u,v,d)
def rotate(p,o,r):
 x,y,z=[p[i]-o[i] for i in range(3)]
 for axis,deg in enumerate(r):
  a=math.radians(deg);c=math.cos(a);s=math.sin(a)
  if axis==0:y,z=y*c-z*s,y*s+z*c
  elif axis==1:x,z=x*c+z*s,-x*s+z*c
  else:x,y=x*c-y*s,x*s+y*c
 return [x+o[0],y+o[1],z+o[2]]
faces=[([0,1,3,2],.58),([4,6,7,5],.78),([0,4,5,1],.65),([2,3,7,6],1.18),([0,2,6,4],.8),([1,5,7,3],.95)]
polys=[]
for e in model['elements']:
 if e['type']=='mesh':
  verts=e['vertices']
  center=np.mean(list(verts.values()),axis=0)
  launcher_x=parents[e['uuid']]
  smooth_shell=False
  smooth_rocket=e['name'].startswith(('short_conical_warhead_','rocket_base_band_','recessed_javelin_shank_'))
  for face in e['faces'].values():
   wp=[verts[v] for v in face['vertices']]
   normal=np.cross(np.array(wp[1])-wp[0],np.array(wp[2])-wp[0]);norm=np.linalg.norm(normal)
   normal=normal/norm if norm else np.array([0.,1.,0.])
   if np.dot(normal,view)<0:normal=-normal
   normals=[]
   for p in wp:
    if smooth_shell and abs(normal[2])<.8:
     nn=np.array([p[0]-launcher_x,p[1]-18.5,0.]);nn/=max(np.linalg.norm(nn),1e-6)
    elif smooth_rocket and abs(normal[2])<.95:
     nn=np.array([p[0]-center[0],p[1]-center[1],0.]);ll=np.linalg.norm(nn);nn=nn/max(ll,1e-6);nn[2]=-.17 if e['name'].startswith('short_conical') else 0;nn/=max(np.linalg.norm(nn),1e-6)
    else:nn=normal
    normals.append(nn)
   ps=[project(p) for p in wp]
   polys.append((ps,np.array([face['uv'][v] for v in face['vertices']]),np.array(normals),e['color']))
  continue
 lo,hi=e['from'],e['to']
 pts=[rotate([hi[0] if i&1 else lo[0],hi[1] if i&2 else lo[1],hi[2] if i&4 else lo[2]],e['origin'],e['rotation']) for i in range(8)]
 pts=[project(p) for p in pts]
 rgb=tuple(int(palette[e['color']][i:i+2],16) for i in [0,2,4])
 for indices,shade in faces:
  ps=[pts[i] for i in indices]
  polys.append((ps,np.array([[e['color']*32+4,4]]*4),np.array([[0,1,0]]*4),e['color']))
S=2;ww,hh=1200*S,1000*S
im=Image.new('RGB',(ww,hh),(186,183,195));draw=ImageDraw.Draw(im)
draw.ellipse((180*S,720*S,1020*S,923*S),fill=(155,150,167))
im=im.filter(ImageFilter.GaussianBlur(17*S));pixels=np.array(im);depth=np.full((hh,ww),-np.inf)
scale=21*S
for ps,uvs,normals,material in polys:
 xy=np.array([(600*S+p[0]*scale,770*S-p[1]*scale,p[2]) for p in ps])
 for i in range(1,len(xy)-1):
  a,b,c=xy[0],xy[i],xy[i+1]
  xmin=max(0,int(min(a[0],b[0],c[0])));xmax=min(ww-1,int(max(a[0],b[0],c[0]))+1)
  ymin=max(0,int(min(a[1],b[1],c[1])));ymax=min(hh-1,int(max(a[1],b[1],c[1]))+1)
  if xmax<xmin or ymax<ymin:continue
  xx,yy=np.meshgrid(np.arange(xmin,xmax+1)+.5,np.arange(ymin,ymax+1)+.5)
  den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
  if abs(den)<1e-8:continue
  u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
  v=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den;w=1-u-v
  zz=u*a[2]+v*b[2]+w*c[2];sub=depth[ymin:ymax+1,xmin:xmax+1]
  mask=(u>=-1e-6)&(v>=-1e-6)&(w>=-1e-6)&(zz>sub)
  if not np.any(mask):continue
  uv=u[:,:,None]*uvs[0]+v[:,:,None]*uvs[i]+w[:,:,None]*uvs[i+1]
  tx=np.clip(uv[:,:,0].astype(int),0,texture.shape[1]-1);ty=np.clip(uv[:,:,1].astype(int),0,texture.shape[0]-1)
  rgb=texture[ty,tx].astype(float)
  nn=u[:,:,None]*normals[0]+v[:,:,None]*normals[i]+w[:,:,None]*normals[i+1]
  nn/=np.maximum(np.linalg.norm(nn,axis=2,keepdims=True),1e-6)
  diffuse=np.maximum(0,np.sum(nn*light,axis=2))
  spec=np.maximum(0,np.sum(nn*halfway,axis=2))**(38 if material==2 else 20)
  shine=65 if material==2 else (22 if material>=3 else 12)
  col=np.clip(rgb*(.53+.8*diffuse[:,:,None])+spec[:,:,None]*shine,0,255).astype(np.uint8)
  sub[mask]=zz[mask];pixels[ymin:ymax+1,xmin:xmax+1][mask]=col[mask]
valid=np.isfinite(depth);occlusion=np.zeros_like(depth)
for dy,dx in [(-6,0),(6,0),(0,6),(0,-6),(-4,-4),(4,4),(-4,4),(4,-4)]:
 neighbor=np.roll(depth,(dy,dx),(0,1))
 delta=np.zeros_like(depth);both=valid&np.isfinite(neighbor);delta[both]=neighbor[both]-depth[both]
 occlusion+=((delta>.16)&(delta<2.6)&both)*.04
pixels[valid]=(pixels[valid].astype(float)*(1-occlusion[valid,None])).astype(np.uint8)
im=Image.fromarray(pixels).resize((1200,1000),Image.Resampling.LANCZOS)
out='D:/ex-twins-captures/violet-turret-preview.png'
os.makedirs(os.path.dirname(out),exist_ok=True);im.save(out)
print(out)
