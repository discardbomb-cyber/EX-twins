import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';

const out = path.resolve('artifacts/blockbench/rf_workbench');
fs.mkdirSync(out, {recursive:true});
const id = () => crypto.randomUUID();
const palette=['101820','20313e','536674','142835','087e96','38e8ff','d8fcff','ffb347'];
const W=128,H=128,raw=Buffer.alloc((W*4+1)*H);
for(let y=0;y<H;y++) for(let x=0;x<W;x++) {
  const band=Math.floor(x/16), noise=((x*17+y*31)%13===0)?7:0;
  const c=palette[band]; const p=y*(W*4+1)+1+x*4;
  for(let k=0;k<3;k++)raw[p+k]=Math.min(255,parseInt(c.slice(k*2,k*2+2),16)+noise);
  raw[p+3]=255;
}
function crc(b){let c=0xffffffff;for(const v of b){c^=v;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^0xffffffff)>>>0;}
function chunk(type,data){const t=Buffer.from(type),l=Buffer.alloc(4),c=Buffer.alloc(4);l.writeUInt32BE(data.length);c.writeUInt32BE(crc(Buffer.concat([t,data])));return Buffer.concat([l,t,data,c]);}
const ih=Buffer.alloc(13);ih.writeUInt32BE(W);ih.writeUInt32BE(H,4);ih[8]=8;ih[9]=6;
const png=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ih),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
fs.writeFileSync(path.join(out,'rf_palette.png'),png);
const elements=[], groups=[];
function group(name,origin,parent){const g={name,origin,rotation:[0,0,0],uuid:id(),export:true,isOpen:true,visibility:true,autouv:0,children:[]};(parent?parent.children:groups).push(g);return g;}
function box(g,name,from,to,color=1,rotation=[0,0,0],origin=g.origin){
 const uuid=id(),faces={};for(const f of ['north','south','east','west','up','down'])faces[f]={uv:[color*16+2,2,color*16+14,126],texture:0};
 elements.push({name,uuid,type:'cube',from,to,origin,rotation,inflate:0,shade:true,visibility:true,export:true,color,autouv:0,faces});g.children.push(uuid);
}
function mesh(g,name,points,polygons,color=1){
 const vertices={},faces={},uuid=id();points.forEach((p,i)=>vertices['v'+i]=p);
 polygons.forEach((p,i)=>{const vs=p.map(j=>'v'+j),uv={};vs.forEach((v,j)=>uv[v]=[color*16+3+(j%2)*9,4+(j>1?9:0)]);faces['f'+i]={vertices:vs,uv,texture:0};});
 elements.push({name,type:'mesh',uuid,origin:[0,0,0],rotation:[0,0,0],vertices,faces,color,visibility:true,export:true});g.children.push(uuid);
}
function radial(g,name,rings,color,n=64){
 const pts=[];for(const [r,y]of rings)for(let i=0;i<n;i++){const a=i*2*Math.PI/n;pts.push([r*Math.sin(a),y,r*Math.cos(a)]);}
 const faces=[];for(let k=0;k<rings.length-1;k++)for(let i=0;i<n;i++)faces.push([k*n+i,k*n+(i+1)%n,(k+1)*n+(i+1)%n,(k+1)*n+i]);
 mesh(g,name,pts,faces,color);
}
function extrusion(g,name,poly,y0,y1,color,angle=0){
 const a=angle*Math.PI/180,points=[];for(const y of [y0,y1])for(const [x,z]of poly)points.push([x*Math.cos(a)+z*Math.sin(a),y,-x*Math.sin(a)+z*Math.cos(a)]);
 const n=poly.length,faces=[Array.from({length:n},(_,i)=>n-1-i),Array.from({length:n},(_,i)=>n+i)];for(let i=0;i<n;i++)faces.push([i,(i+1)%n,(i+1)%n+n,i+n]);mesh(g,name,points,faces,color);
}
function tube(g,name,start,end,radius,color=2,n=12){
 const sub=(a,b)=>a.map((v,i)=>v-b[i]),cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],unit=a=>{const l=Math.hypot(...a);return a.map(v=>v/l);};
 const axis=unit(sub(end,start)),u=unit(cross(axis,Math.abs(axis[1])<.9?[0,1,0]:[1,0,0])),v=cross(axis,u),points=[];
 for(const center of [start,end])for(let i=0;i<n;i++){const a=i*2*Math.PI/n;points.push(center.map((p,k)=>p+radius*(u[k]*Math.cos(a)+v[k]*Math.sin(a))));}
 const faces=[Array.from({length:n},(_,i)=>n-1-i),Array.from({length:n},(_,i)=>n+i)];for(let i=0;i<n;i++)faces.push([i,(i+1)%n,(i+1)%n+n,i+n]);mesh(g,name,points,faces,color);
}
function bolt(g,name,p,axis=[0,1,0],r=.19){
 tube(g,name+'_washer',p,p.map((v,i)=>v+axis[i]*.07),r*1.45,1,12);
 const top=p.map((v,i)=>v+axis[i]*.15);tube(g,name+'_hex',p,top,r,2,6);
 tube(g,name+'_socket',top,top.map((v,i)=>v+axis[i]*.008),r*.42,0,6);
}

// V2: three-sided shell with retracting armour, triangular frame and four orbiting nodes.
const add=(a,b)=>a.map((v,i)=>v+b[i]),sub=(a,b)=>a.map((v,i)=>v-b[i]),mul=(a,s)=>a.map(v=>v*s);
const unit=a=>mul(a,1/Math.hypot(...a));
const cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]];
const lerp=(a,b,t)=>add(mul(a,1-t),mul(b,t));
const rim=Array.from({length:3},(_,i)=>[11.4*Math.sin(i*Math.PI*2/3),1.8,11.4*Math.cos(i*Math.PI*2/3)]),apex=[0,10.5,0];
function plate(g,name,poly,normal,depth,color,shrink=.92){
 const center=mul(poly.reduce(add,[0,0,0]),1/poly.length);
 const inner=poly.map(p=>add(lerp(center,p,shrink),mul(normal,depth)));
 const pts=[...poly,...inner],n=poly.length,faces=[Array.from({length:n},(_,i)=>n-1-i),Array.from({length:n},(_,i)=>n+i)];
 for(let i=0;i<n;i++)faces.push([i,(i+1)%n,(i+1)%n+n,i+n]);
 mesh(g,name,pts,faces,color);
}
const base=group('foundation_fixed',[0,0,0]);
const footprint=scale=>rim.map(p=>[p[0]*scale,p[2]*scale]);
extrusion(base,'triangular_lower_chassis',footprint(1.08),0,.65,0);
extrusion(base,'bevelled_foundation',footprint(1.05),.65,1.15,2);
extrusion(base,'recessed_deck',footprint(1),1.15,1.65,1);
extrusion(base,'inner_triangular_plinth',footprint(.76),1.65,1.85,0);
for(let i=0;i<3;i++){
 const a=rim[i],b=rim[(i+1)%3];
 for(const [r,y,c]of [[1.04,.95,4],[.92,1.68,2],[.72,1.86,4]])tube(base,`deck_track_${i}_${r}`,a.map((v,k)=>k===1?y:v*r),b.map((v,k)=>k===1?y:v*r),.07,c,6);
 for(let j=0;j<12;j++){
  const p=lerp(a,b,(j+.5)/12),q=mul(p,.91);p[1]=1.3;q[1]=1.3;tube(base,`deck_vent_${i}_${j}`,p,q,.055,0,4);
 }
 for(const t of [.12,.32,.68,.88]){const p=lerp(a,b,t);p[1]=1.73;bolt(base,`deck_fastener_${i}_${t}`,p,[0,1,0],.12);}
}
radial(base,'core_socket',[[0,1.85],[1.65,1.85],[1.9,2.05],[1.9,2.3],[1.45,2.4],[0,2.4]],2,24);
radial(base,'socket_cyan_channel',[[1.5,2.41],[1.72,2.41]],4,24);
const panels=[],cells=[],nodes=[];
for(let i=0;i<3;i++){
 const A=rim[i],B=rim[(i+1)%3],C=apex,hinge=lerp(A,B,.5),normal=unit(cross(sub(B,A),sub(C,A)));
 // Face normal points outside the tetrahedron.
 if(normal[0]*hinge[0]+normal[2]*hinge[2]<0)for(let k=0;k<3;k++)normal[k]*=-1;
 const panel=group(`frame_sector_${i}`,hinge);panels.push(panel);
 const glow=group(`energised_cells_${i}`,hinge,panel);cells.push(glow);
 const f=(u,v,h=0)=>add(add(mul(A,1-u-v),add(mul(B,u),mul(C,v))),mul(normal,h));
 const outer=[f(.075,.055),f(.87,.055),f(.08,.83)],inner=[f(.17,.15),f(.70,.15),f(.17,.65)];
 for(let e=0;e<3;e++){
  const n=(e+1)%3;
  plate(panel,`forged_frame_${i}_${e}`,[outer[e],outer[n],inner[n],inner[e]],normal,.36,1);
  tube(glow,`frame_light_${i}_${e}`,lerp(outer[e],inner[e],.68),lerp(outer[n],inner[n],.68),.055,5,6);
  tube(panel,`silver_inner_bevel_${i}_${e}`,inner[e],inner[n],.045,2,6);
  for(let j=0;j<10;j++){
   const t=(j+.5)/10,p=lerp(outer[e],outer[n],t),q=lerp(inner[e],inner[n],Math.min(.98,t+.025));
   tube(panel,`frame_rib_${i}_${e}_${j}`,add(p,mul(normal,.38)),add(lerp(p,q,.6),mul(normal,.38)),.048,j%3===0?7:2,4);
  }
 }
 // Lightning sockets remain attached to the inner frame through every animation.
 for(let edge=0;edge<3;edge++){
  const midpoint=lerp(inner[edge],inner[(edge+1)%3],.5),inside=mul(inner.reduce(add,[0,0,0]),1/3);
  group(`wall_contact_${i}_${edge}`,add(lerp(midpoint,inside,.035),mul(normal,.02)),panel);
 }
 for(const [u,v]of [[.13,.07],[.8,.07],[.08,.74]])bolt(panel,`frame_lock_${i}_${u}`,f(u,v,.4),normal,.11);
}
// Corner clusters have three bevelled cheeks, conduits, sockets and replaceable fasteners.
for(const [i,p]of [...rim,apex].entries()){
 const g=group(`orbit_corner_${i}`,p);nodes.push(g);
 const toward=unit(sub([0,5,0],p)),u=unit(cross(toward,Math.abs(toward[1])>.9?[1,0,0]:[0,1,0])),v=cross(toward,u);
 const q=(x,y,z)=>add(p,add(mul(u,x),add(mul(v,y),mul(toward,z))));
 for(let k=0;k<3;k++){
  const a=k*2*Math.PI/3,b=(k+1)*2*Math.PI/3;
  const poly=[q(0,0,-.08),q(1.2*Math.cos(a),1.2*Math.sin(a),.6),q(1.2*Math.cos(b),1.2*Math.sin(b),.6),q(0,0,1.45)];
  const norm=unit(cross(sub(poly[1],poly[0]),sub(poly[2],poly[0])));
  plate(g,`corner_cheek_${i}_${k}`,poly,norm,.15,2);
  const inner=poly.map(pt=>lerp(mul(poly.reduce(add,[0,0,0]),.25),pt,.65));
  for(let e=0;e<4;e++)tube(g,`corner_circuit_${i}_${k}_${e}`,add(inner[e],mul(norm,.17)),add(inner[(e+1)%4],mul(norm,.17)),.037,4,6);
  bolt(g,`corner_bolt_${i}_${k}`,lerp(poly[1],poly[2],.5),norm,.09);
 }
 tube(g,`corner_coupling_${i}`,q(0,0,1.1),q(0,0,1.9),.3,0,12);
 tube(g,`corner_cap_${i}`,q(0,0,1.7),q(0,0,1.86),.33,7,6);
}
const core=group('pulsing_core',[0,7,0]);
const center=[0,7,0],point=(lat,lon,r=1.15)=>add(center,[r*Math.cos(lat)*Math.sin(lon),r*Math.sin(lat),r*Math.cos(lat)*Math.cos(lon)]);
const sphereVertices=[],sphereFaces=[],rows=16,cols=32;
for(let j=0;j<=rows;j++)for(let k=0;k<cols;k++)sphereVertices.push(point(-Math.PI/2+j*Math.PI/rows,k*Math.PI*2/cols,1.08));
for(let j=0;j<rows;j++)for(let k=0;k<cols;k++){
 const a=j*cols+k,b=j*cols+(k+1)%cols,c=(j+1)*cols+(k+1)%cols,d=(j+1)*cols+k;
 if(j===0)sphereFaces.push([a,c,d]);else if(j===rows-1)sphereFaces.push([a,b,c]);else sphereFaces.push([a,b,c,d]);
}
mesh(core,'dark_circuit_sphere',sphereVertices,sphereFaces,0);
// Circuit traces lie on the curved surface; right-angle turns and plated terminal pads.
for(let k=0;k<18;k++){
 let lon=k*Math.PI*2/18,lat=-1.10+(k%3)*.19;
 const steps=12;
 for(let j=0;j<steps;j++){
  const horizontal=j%3===1,nextLat=lat+(horizontal?0:.14),nextLon=lon+(horizontal?(k%2?-.10:.10):0),a=point(lat,lon),b=point(nextLat,nextLon);
  tube(core,`circuit_trace_${k}_${j}`,a,b,.018,j%5===0?6:5,5);
  if(j%4===0){const n=unit(sub(a,center));tube(core,`circuit_terminal_${k}_${j}`,a,add(a,mul(n,.016)),.05,6,8);tube(core,`terminal_inset_${k}_${j}`,add(a,mul(n,.018)),add(a,mul(n,.021)),.024,4,8);}
  lat=nextLat;lon=nextLon;
 }
}
for(let ring=0;ring<3;ring++)for(let k=0;k<48;k++){
 const lat=-.8+ring*.78,a=k*Math.PI*2/48;
 if(k%12<9)tube(core,`circuit_bus_${ring}_${k}`,point(lat,a,1.17),point(lat,a+Math.PI*2/48,1.17),.023,4,5);
}
const golden=(1+Math.sqrt(5))/2;
const icoRaw=[[-1,golden,0],[1,golden,0],[-1,-golden,0],[1,-golden,0],[0,-1,golden],[0,1,golden],[0,-1,-golden],[0,1,-golden],[golden,0,-1],[golden,0,1],[-golden,0,-1],[-golden,0,1]];
const ico=icoRaw.map(p=>add(center,mul(unit(p),1.8)));
const icoFaces=[[0,11,5],[0,5,1],[0,1,7],[0,7,10],[0,10,11],[1,5,9],[5,11,4],[11,10,2],[10,7,6],[7,1,8],[3,9,4],[3,4,2],[3,2,6],[3,6,8],[3,8,9],[4,9,5],[2,4,11],[6,2,10],[8,6,7],[9,8,1]];
const edges=new Set();
for(const [i,face]of icoFaces.entries()){
 const tri=face.map(k=>ico[k]),mid=mul(tri.reduce(add,[0,0,0]),1/3),normal=unit(sub(mid,center));
 const inset=tri.map(p=>lerp(mid,p,.84));
 plate(core,`glass_shell_${i}`,inset,normal,.025,1);
 const e=elements.at(-1);e.opacity=.18;for(const f of Object.values(e.faces))f.texture=1;
 for(let k=0;k<3;k++){
  const a=face[k],b=face[(k+1)%3],key=[a,b].sort((x,y)=>x-y).join(':');
  if(!edges.has(key)){edges.add(key);tube(core,`crystal_edge_${key}`,ico[a],ico[b],.022,4,5);}
  if(i%3===0)tube(core,`crystal_fracture_${i}_${k}`,lerp(inset[k],mid,.25),lerp(inset[(k+1)%3],mid,.4),.017,5,5);
 }
}
for(let i=0;i<4;i++)group(`core_contact_${i}`,ico[[0,3,5,8][i]],core);
const animations=[];
function anim(name,length,loop,tracks){const animators={};for(const [g,channel,frames]of tracks){const b=animators[g.uuid]??={name:g.name,type:'bone',keyframes:[]};for(const [time,v]of frames)b.keyframes.push({uuid:id(),channel,time,interpolation:'linear',data_points:[{x:v[0].toFixed(6),y:v[1].toFixed(6),z:v[2].toFixed(6)}]});}animations.push({uuid:id(),name,loop,override:false,length,snapping:24,animators});}
const constant=(gs,c,v)=>gs.map(g=>[g,c,[[0,v]]]);
const openOffset=g=>[0,3.7,0];
const openTracks=[...panels.map(g=>[g,'position',[[0,openOffset(g)]]]),...constant(cells,'scale',[1,1,1]),[nodes[3],'position',[[0,[0,3.7,0]]]]];
anim('uncharged',1,'loop',[...constant(cells,'scale',[.001,.001,.001]),[core,'scale',[[0,[.001,.001,.001]]]]]);
anim('charged_closed',1,'loop',[...constant(cells,'scale',[1,1,1]),[core,'scale',[[0,[.001,.001,.001]]]]]);
anim('player_approach',1.6,'hold',[...panels.map(g=>[g,'position',[[0,[0,0,0]],[1.6,openOffset(g)]]]),...constant(cells,'scale',[1,1,1]),[nodes[3],'position',[[0,[0,0,0]],[1.6,[0,3.7,0]]]],[core,'scale',[[0,[.001,.001,.001]],[1.6,[.65,.65,.65]]]]]);
anim('charged_open',3,'loop',[...openTracks,[core,'scale',[[0,[.65,.65,.65]],[1.5,[.7,.7,.7]],[3,[.65,.65,.65]]]]]);
anim('player_leave',1.6,'hold',[...panels.map(g=>[g,'position',[[0,openOffset(g)],[1.6,[0,0,0]]]]),...constant(cells,'scale',[1,1,1]),[nodes[3],'position',[[0,[0,3.7,0]],[1.6,[0,0,0]]]],[core,'scale',[[0,[.65,.65,.65]],[1.6,[.001,.001,.001]]]]]);
const orbit=[];
for(let i=0;i<4;i++){
 const frames=[];for(let k=0;k<=96;k++){const t=k/24,a=i*Math.PI/2+k*Math.PI/48,p=[3.15*Math.cos(a),7+.95*Math.sin(a*2+i),3.15*Math.sin(a)];frames.push([t,sub(p,nodes[i].origin)]);}
 orbit.push([nodes[i],'position',frames],[nodes[i],'rotation',[[0,[0,i*90,0]],[4,[0,i*90+360,0]]]]);
}
anim('crafting',4,'loop',[...openTracks.filter(t=>t[0]!==nodes[3]),...orbit,[core,'rotation',[[0,[0,0,0]],[4,[0,-360,0]]]],[core,'scale',Array.from({length:9},(_,i)=>[i*.5,Array(3).fill(i%2?1:.78)])]]);
// Ease into the exact start/end pose of the orbit so nodes never teleport between states.
const detach=[],returnTracks=[];
for(let i=0;i<4;i++){
 const atOpen=i===3?[0,3.7,0]:[0,0,0],a=i*Math.PI/2;
 const atOrbit=sub([3.15*Math.cos(a),7+.95*Math.sin(2*a+i),3.15*Math.sin(a)],nodes[i].origin);
 const keys=[],back=[];
 for(let k=0;k<=24;k++){const t=k/24,e=t*t*(3-2*t);keys.push([t*1.2,lerp(atOpen,atOrbit,e)]);back.push([t*1.2,lerp(atOrbit,atOpen,e)]);}
 detach.push([nodes[i],'position',keys],[nodes[i],'rotation',[[0,[0,0,0]],[1.2,[0,i*90,0]]]]);
 returnTracks.push([nodes[i],'position',back],[nodes[i],'rotation',[[0,[0,i*90,0]],[1.2,[0,0,0]]]]);
}
const staticOpen=openTracks.filter(t=>t[0]!==nodes[3]);
anim('crafting_start',1.2,'hold',[...staticOpen,...detach,[core,'scale',[[0,[.65,.65,.65]],[1.2,[.78,.78,.78]]]]]);
anim('crafting_end',1.2,'hold',[...staticOpen,...returnTracks,[core,'scale',[[0,[.78,.78,.78]],[1.2,[.65,.65,.65]]]]]);
// Normalize the triangular foundation to a 32x32-unit (2x2-block) footprint.
// Preserve a round core while slightly stretching the frame in Z to fit the requested square bounds.
const baseIds=new Set(base.children.filter(x=>typeof x==='string'));
const basePoints=elements.filter(e=>baseIds.has(e.uuid)).flatMap(e=>Object.values(e.vertices));
const minX=Math.min(...basePoints.map(p=>p[0])),maxX=Math.max(...basePoints.map(p=>p[0]));
const minZ=Math.min(...basePoints.map(p=>p[2])),maxZ=Math.max(...basePoints.map(p=>p[2]));
const sx=32/(maxX-minX),sz=32/(maxZ-minZ),sy=Math.min(sx,sz),cx=(minX+maxX)/2,cz=(minZ+maxZ)/2;
const scaled=p=>[(p[0]-cx)*sx,p[1]*sy,(p[2]-cz)*sz];
const oldCore=[...core.origin],newCore=scaled(oldCore),coreIds=new Set();
function collectCore(nodes){for(const g of nodes)if(typeof g==='string')coreIds.add(g);else collectCore(g.children);}collectCore(core.children);
const scaledCore=p=>add(newCore,mul(sub(p,oldCore),sy));
for(const e of elements)for(const [key,p]of Object.entries(e.vertices))e.vertices[key]=(coreIds.has(e.uuid)?scaledCore:scaled)(p);
function scaleBones(nodes,inCore=false){for(const g of nodes)if(typeof g!=='string'){const isCore=inCore||g===core;g.origin=(isCore?scaledCore:scaled)(g.origin);scaleBones(g.children,isCore);}}scaleBones(groups);
for(const a of animations)for(const bone of Object.values(a.animators))for(const k of bone.keyframes)if(k.channel==='position')for(const p of k.data_points){p.x=(Number(p.x)*sx).toFixed(6);p.y=(Number(p.y)*sy).toFixed(6);p.z=(Number(p.z)*sz).toFixed(6);}
const glassRaw=Buffer.from(raw);for(let y=0;y<H;y++)for(let x=0;x<W;x++)glassRaw[y*(W*4+1)+1+x*4+3]=46;
const glassPng=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ih),chunk('IDAT',zlib.deflateSync(glassRaw)),chunk('IEND',Buffer.alloc(0))]);
const texture=(name,index,data,mode)=>({name,id:String(index),uuid:id(),source:'data:image/png;base64,'+data.toString('base64'),width:W,height:H,uv_width:W,uv_height:H,render_mode:mode,render_sides:'double',visible:true,internal:true,saved:true});
const model={meta:{format_version:'4.10',model_format:'free',box_uv:false},name:'RF Workbench V4',model_identifier:'rf_workbench',visible_box:[4,4,0],resolution:{width:W,height:H},elements,outliner:groups,textures:[texture('rf_palette.png',0,png,'default'),texture('rf_glass.png',1,glassPng,'default')],animations};
const target=path.join(out,'rf_workbench.bbmodel');
const ids=new Set(elements.map(e=>e.uuid)),linked=new Set(),bones=new Set();
function check(gs){for(const g of gs)if(typeof g==='string'){if(!ids.has(g)||linked.has(g))throw Error('Invalid element reference');linked.add(g);}else{bones.add(g.uuid);check(g.children);}}check(groups);
if(linked.size!==elements.length)throw Error('Unattached geometry');
for(const e of elements)if(e.type==='mesh'){for(const p of Object.values(e.vertices))if(p.some(v=>!Number.isFinite(v)))throw Error('Invalid vertex');for(const f of Object.values(e.faces))for(const v of f.vertices)if(!e.vertices[v]||!f.uv[v])throw Error('Invalid face');}
for(const a of animations)for(const [uuid,b]of Object.entries(a.animators)){if(!bones.has(uuid))throw Error('Invalid bone');for(const k of b.keyframes)if(k.time<0||k.time>a.length)throw Error('Invalid time');}
fs.writeFileSync(target,JSON.stringify(model,null,2));
// Runtime model shares the exact vertices and numeric animation keys with Blockbench.
const resourceRoot=path.resolve('src/main/resources/assets/relics_addon');
fs.mkdirSync(path.join(resourceRoot,'workbench'),{recursive:true});
fs.mkdirSync(path.join(resourceRoot,'textures/workbench'),{recursive:true});
fs.writeFileSync(path.join(resourceRoot,'workbench/rf_workbench.json'),JSON.stringify({footprint:[2,2],elements,outliner:groups,animations}));
fs.writeFileSync(path.join(resourceRoot,'textures/workbench/rf_palette.png'),png);
fs.writeFileSync(path.join(resourceRoot,'textures/workbench/rf_glass.png'),glassPng);
const sequence=[['uncharged',1.5],['charged_closed',.6],['player_approach',1.6],['charged_open',1],['crafting_start',1.2],['crafting',4],['crafting_end',1.2],['charged_open',.8],['player_leave',1.6],['charged_closed',.5]];
fs.writeFileSync(path.join(out,'preview_sequence.json'),JSON.stringify(sequence));
const normalized=elements.filter(e=>baseIds.has(e.uuid)).flatMap(e=>Object.values(e.vertices));
const width=(Math.max(...normalized.map(p=>p[0]))-Math.min(...normalized.map(p=>p[0])))/16;
const depth=(Math.max(...normalized.map(p=>p[2]))-Math.min(...normalized.map(p=>p[2])))/16;
if(Math.abs(width-2)>1e-6||Math.abs(depth-2)>1e-6)throw Error('Incorrect footprint');
console.log(`PASS: ${elements.length} meshes, ${animations.length} clips; foundation ${width.toFixed(6)}x${depth.toFixed(6)} blocks; no retracting armour. ${target}`);



