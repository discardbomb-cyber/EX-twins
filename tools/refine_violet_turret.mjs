import fs from 'node:fs';
import crypto from 'node:crypto';
import zlib from 'node:zlib';
const file='artifacts/blockbench/violet_turret/violet_javelin_turret.bbmodel';
const model=JSON.parse(fs.readFileSync(file,'utf8'));
const old={};function walk(gs){for(const g of gs)if(typeof g!=='string'){old[g.name]=g;walk(g.children);}}walk(model.outliner);
const elements=[],root=[];const id=()=>crypto.randomUUID();
function group(name,origin,parent){const g={name,origin,rotation:[0,0,0],uuid:old[name]?.uuid??id(),export:true,isOpen:true,visibility:true,children:[]};(parent?parent.children:root).push(g);return g;}
function mesh(g,name,points,polys,color){const vertices={},faces={};points.forEach((p,i)=>vertices['v'+i]=p);polys.forEach((p,i)=>{
 const vs=p.map(j=>'v'+j),uv={},pp=p.map(j=>points[j]),a=pp[0],b=pp[1],c=pp[2],ab=b.map((v,k)=>v-a[k]),ac=c.map((v,k)=>v-a[k]),n=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]],drop=n.map(Math.abs).indexOf(Math.max(...n.map(Math.abs))),axes=[0,1,2].filter(k=>k!==drop);
 const min=axes.map(k=>Math.min(...pp.map(v=>v[k]))),max=axes.map(k=>Math.max(...pp.map(v=>v[k])));
 vs.forEach((v,j)=>uv[v]=[color*32+2+((pp[j][axes[0]]-min[0])/(max[0]-min[0]||1))*27,4+((pp[j][axes[1]]-min[1])/(max[1]-min[1]||1))*247]);
 faces['f'+i]={vertices:vs,uv,texture:0};});const uuid=id();elements.push({name,type:'mesh',uuid,origin:[0,0,0],rotation:[0,0,0],vertices,faces,color,visibility:true,export:true});g.children.push(uuid);}
function loft(g,name,layers,color,closed=true){const n=layers[0].length,ps=layers.flat(),fs=[];if(closed)fs.push(Array.from({length:n},(_,i)=>n-1-i),Array.from({length:n},(_,i)=>(layers.length-1)*n+i));for(let l=0;l<layers.length-1;l++)for(let i=0;i<n;i++)fs.push([l*n+i,l*n+(i+1)%n,(l+1)*n+(i+1)%n,(l+1)*n+i]);mesh(g,name,ps,fs,color);}
function ring(g,name,profile,color,n=96){const layers=profile.map(([r,y])=>Array.from({length:n},(_,i)=>[r*Math.sin(i*2*Math.PI/n),y,r*Math.cos(i*2*Math.PI/n)]));loft(g,name,layers,color,false);}
function tube(g,name,a,b,r,color,n=16){const d=b.map((v,i)=>v-a[i]),len=Math.hypot(...d),u=d.map(v=>v/len),cross=(a,b)=>[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]],normalize=a=>{const l=Math.hypot(...a);return a.map(v=>v/l);},v=normalize(cross(u,Math.abs(u[1])<.9?[0,1,0]:[1,0,0])),w=cross(u,v);loft(g,name,[a,b].map(p=>Array.from({length:n},(_,i)=>p.map((x,k)=>x+r*(Math.cos(i*2*Math.PI/n)*v[k]+Math.sin(i*2*Math.PI/n)*w[k])))),color);}
function fastener(g,name,p,axis,r=.14){const add=(s)=>p.map((v,i)=>v+axis[i]*s);tube(g,name+'_washer',p,add(.065),r*1.45,1,16);tube(g,name+'_head',add(.065),add(.16),r,2,6);tube(g,name+'_recess',add(.16),add(.163),r*.4,0,6);}
function cable(g,name,points,r=.14){for(let i=0;i<points.length-1;i++)tube(g,name+'_'+i,points[i],points[i+1],r,0,10);for(const p of [points[0],points.at(-1)])tube(g,name+'_connector',p,p.map((v,k)=>v+(k===1?.3:0)),r*1.8,2,12);}
function arcCable(g,name,a,b,c,r=.14){const ps=Array.from({length:18},(_,i)=>{const t=i/17;return a.map((v,k)=>(1-t)**2*v+2*(1-t)*t*b[k]+t*t*c[k]);});cable(g,name,ps,r);}
const turn=(p,a)=>{const c=Math.cos(a),s=Math.sin(a);return[p[0]*c+p[2]*s,p[1],-p[0]*s+p[2]*c];};
const base=group('base',[0,0,0]);
ring(base,'smooth_circular_platform',[[0,.1],[13.2,.1],[13.6,.3],[13.85,.7],[13.9,1.7],[13.8,2.25],[13.5,2.6],[0,2.6]],0);
ring(base,'rounded_outer_lip',[[12.25,2.58],[13.3,2.58],[13.55,2.75],[13.65,3],[13.58,3.22],[13.35,3.4],[12.35,3.4],[12.15,3.2],[12.25,2.58]],2);
ring(base,'violet_annular_inset',[[12.45,3.41],[13.18,3.41]],6);
ring(base,'inner_recess',[[0,2.61],[11.95,2.61]],1);
ring(base,'foundation_sealing_groove',[[13.87,1.15],[13.96,1.15],[13.96,1.35],[13.87,1.35]],1);
ring(base,'inner_deck_service_track',[[9.15,2.63],[9.35,2.63]],2);
for(let i=0;i<24;i++){
 const a=i*Math.PI/12;fastener(base,'rim_flush_screw_'+i,[13.36*Math.sin(a),3.39,13.36*Math.cos(a)],[0,1,0],.12);
 const p0=turn([-.032,2.64,9.45],a),p1=turn([.032,2.64,9.45],a),p2=turn([.032,2.64,11.8],a),p3=turn([-.032,2.64,11.8],a);mesh(base,'deck_expansion_joint',[p0,p1,p2,p3],[[0,1,2,3]],0);
}
// Four broad petals: curved tips and sloping rims, no rectangular blocks.
const foot=[];foot.push([-4.25,10]);foot.push([-4.25,17.8]);for(let i=0;i<=20;i++){const a=Math.PI-i*Math.PI/20;foot.push([4.25*Math.cos(a),17.8+1.9*Math.sin(a)]);}foot.push([4.25,10]);
for(let i=0;i<4;i++){
 const angle=Math.PI/4+i*Math.PI/2;
 loft(base,'curved_outrigger_'+i,[[.9,.2],[1,.5],[1,1.65],[.97,2],[.91,2.3]].map(([s,y])=>foot.map(([x,z])=>turn([x*s,y,10+(z-10)*s],angle))),0);
 const inset=foot.map(([x,z])=>[x*.73,12+(z-10)*.7]);
 loft(base,'petal_panel_'+i,[2.305,2.38].map(y=>inset.map(([x,z])=>turn([x,y,z],angle))),5);
 // Raised perimeter is beveled rather than a box-shaped cap.
 loft(base,'petal_rim_'+i,[[.9,2.3],[.82,2.42]].map(([s,y])=>foot.map(([x,z])=>turn([x*s,y,10.8+(z-10)*s],angle))),2);
 loft(base,'petal_panel_surface_'+i,[2.425,2.44].map(y=>inset.map(([x,z])=>turn([x,y,z],angle))),7);
 for(const x of [-3.15,3.15])for(const z of [12.5,17.5])fastener(base,'outrigger_lock_'+i,turn([x,2.4,z],angle),[0,1,0],.16);
 for(const x of [-3.3,3.3])tube(base,'outrigger_shock_absorber',turn([x,1.4,10.8],angle),turn([x,1.4,14.1],angle),.26,2);
 for(const z of [13.25,17.65])mesh(base,'inset_panel_seal',[[-2.85,2.45,z-.04],[2.85,2.45,z-.04],[2.85,2.45,z+.04],[-2.85,2.45,z+.04]].map(p=>turn(p,angle)),[[0,1,2,3]],4);
}
ring(base,'pedestal_flared_foot',[[0,2.65],[7.5,2.65],[8,2.9],[8.2,3.3],[8.15,3.8],[7.8,4],[0,4]],0);
ring(base,'sculpted_pedestal_core',[[7.25,3.8],[7.0,4.1],[6.6,4.7],[6.1,5.6],[5.6,6.8],[5.1,8],[4.9,8.5],[4.65,8.8],[0,8.8]],1,64);
// Eight separated armor wedges over the tapered pedestal, matching the reference.
for(let i=0;i<8;i++){
 const a=i*Math.PI/4,ps=[[-2,4.1,7.1],[2,4.1,7.1],[1.7,7.75,5.3],[-1.7,7.75,5.3],[-2,4.1,7.75],[2,4.1,7.75],[1.7,7.75,5.8],[-1.7,7.75,5.8]];
 mesh(base,'sloped_pedestal_armor_'+i,ps.map(p=>turn(p,a)),[[0,1,2,3],[4,7,6,5],[0,4,5,1],[3,2,6,7],[0,3,7,4],[1,5,6,2]],0);
 tube(base,'pedestal_inner_actuator_'+i,turn([2.3,3.8,6.4],a),turn([1.7,8.15,4.65],a),.3,2);
 tube(base,'pedestal_actuator_sleeve_'+i,turn([2.3,3.8,6.4],a),turn([2.05,5.5,5.75],a),.43,1);
 for(const side of [-1,1])fastener(base,'armor_lock',turn([side*1.6,4.3,7.68],a),turn([0,.44,.9],a),.14);
 arcCable(base,'pedestal_power_hose',turn([-2.4,4.2,6.45],a),turn([-3.1,5.9,7.1],a),turn([-1.75,7.9,4.9],a),.13);
 for(let j=0;j<4;j++){
  const y=3.8+j*.32;mesh(base,'pedestal_heat_sink', [[-1.5,y,7.9],[1.5,y,7.9],[1.5,y+.17,7.7],[-1.5,y+.17,7.7]].map(p=>turn(p,a)),[[0,1,2,3]],2);
 }
}
ring(base,'bearing_beveled_collar',[[0,8.7],[4.8,8.7],[5.05,8.95],[5.05,9.2],[4.85,9.45],[0,9.45]],2);
const yaw=group('yaw',[0,10,0],base);
ring(yaw,'rounded_rotation_head',[[0,9.35],[3.3,9.35],[3.65,9.6],[3.85,10.2],[3.85,10.7],[3.6,11.2],[3.2,11.7],[2.65,12],[2.4,14.4],[0,14.4]],0);
ring(yaw,'rotation_head_accent',[[3.85,10.4],[3.85,10.65]],4);
ring(base,'bearing_seal',[[5.02,9.2],[5.13,9.2],[5.13,9.32],[5.02,9.32]],0);
for(let i=0;i<16;i++){const a=i*Math.PI/8;fastener(base,'bearing_lock',[4.85*Math.sin(a),9.44,4.85*Math.cos(a)],[0,1,0],.105);}
for(const s of [-1,1]){
 tube(yaw,'elevation_drive_motor',[s*2.1,13.5,.6],[s*3.5,13.5,.6],.95,1,32);
 tube(yaw,'drive_motor_cap',[s*3.5,13.5,.6],[s*3.66,13.5,.6],.76,2,32);
 tube(yaw,'elevation_hydraulic_body',[s*2.4,11.3,1.7],[s*2.4,13.4,-1.3],.36,1,24);
 tube(yaw,'elevation_piston_rod',[s*2.4,13.4,-1.3],[s*2.4,14.8,-3.3],.14,2,24);
 arcCable(yaw,'drive_power_cable',[s*3.5,13.4,1],[s*4.6,11.1,1.8],[s*2.5,10.5,2],.15);
}
const pitch=group('pitch',[0,16,0],yaw);
tube(pitch,'horizontal_elevation_axle',[-8,15.5,0],[8,15.5,0],1.6,1,32);
for(const side of [-1,1]){tube(pitch,'rounded_axis_cap',[side*2,15.5,0],[side*3,15.5,0],2,2,32);}
// Regular hexagonal sections along the whole launcher, with a flat upper face.
for(const side of [-1,1]){
 const x=side*5.7,g=group(side<0?'launcher_left':'launcher_right',[x,17,0],pitch);
 const circle=(cx,cy,r,z)=>Array.from({length:6},(_,i)=>[cx+r*Math.cos(i*Math.PI/3),cy+r*Math.sin(i*Math.PI/3),z]);
 loft(g,'hexagonal_launcher_shell',[[-10,3.55],[-9.5,3.75],[9.7,3.75],[10.4,3.65],[10.85,3.4],[11.1,3.15]].map(([z,r])=>circle(x,18.5,r,z)),0);
 const skin=(u,z,r=3.81)=>{
  const theta=Math.PI/2-u/3.75,normal=(Math.floor(theta/(Math.PI/3))+.5)*Math.PI/3;
  const distance=r*Math.cos(Math.PI/6)/Math.cos(theta-normal);
  return[x+distance*Math.cos(theta),18.5+distance*Math.sin(theta),z];
 };
 function patch(name,u0,u1,z0,z1,r,color,n=24){const ps=[];for(const z of [z0,z1])for(let i=0;i<=n;i++)ps.push(skin(u0+(u1-u0)*i/n,z,r));const faces=[];for(let i=0;i<n;i++)faces.push([i,i+1,n+2+i,n+1+i]);mesh(g,name,ps,faces,color);}
 patch('curved_honeycomb_bed',-2.35,2.35,-9.15,9.95,3.81,3);
 for(const s of [-1,1])patch('curved_panel_edge',s*2.36,s*2.66,-9.2,10,3.84,2,4);
 for(const z of [-9.2,10])patch('curved_end_frame',-2.66,2.66,z-.16,z+.16,3.84,2);
 for(let row=0;row<13;row++)for(let col=0;col<3;col++){
  const cx=x-1.65+col*1.6+(row%2?.8:0),z=-8.3+row*1.42,r=.92;
  if(cx+.8>x+2.2||z+r>10)continue;
  const ps=Array.from({length:6},(_,i)=>skin(cx-x+Math.sin(i*Math.PI/3)*r,z+Math.cos(i*Math.PI/3)*r,3.93));
  mesh(g,'hexagonal_panel_cell',ps,[[0,1,2,3,4,5]],(row+col)%4===0?4:3);
  const uv=Array.from({length:6},(_,i)=>[cx-x+Math.sin(i*Math.PI/3)*r,z+Math.cos(i*Math.PI/3)*r]);
  for(let i=0;i<6;i++){const a=uv[i],b=uv[(i+1)%6],dx=b[0]-a[0],dz=b[1]-a[1],len=Math.hypot(dx,dz),w=.032;mesh(g,'curved_honeycomb_seam',[skin(a[0]-dz/len*w,a[1]+dx/len*w,3.94),skin(b[0]-dz/len*w,b[1]+dx/len*w,3.94),skin(b[0]+dz/len*w,b[1]-dx/len*w,3.94),skin(a[0]+dz/len*w,a[1]-dx/len*w,3.94)],[[0,1,2,3]],1);}
 }
 for(const s of [-1,1]){
  patch('curved_side_armor',s*4.3,s*6.4,-8.5,9.3,3.79,1);
  patch('curved_side_trim',s*4.32,s*4.55,-8.5,9.3,3.81,2,4);
 }
 // Hexagonal front and rear collars, surrounding seven launch cells.
 loft(g,'hexagonal_front_collar',[[3.55,-10.02],[3.76,-10.28],[3.76,-10.62],[3.55,-10.82],[3.35,-10.82],[3.35,-10.1]].map(([r,z])=>circle(x,18.5,r,z)),2,false);
 loft(g,'hexagonal_dock_recess',[circle(x,18.5,3.35,-10.83),circle(x,18.5,3.35,-10.85)],0);
 loft(g,'hexagonal_rear_collar',[[3.7,9.7],[3.8,9.9],[3.8,10.12],[3.7,10.32]].map(([r,z])=>circle(x,18.5,r,z)),2,false);
 // Split shell collars, seals and end-plate fasteners follow the circular section.
 for(const z of [-6.8,5.6]){
  loft(g,'shell_joint_collar',[[3.76,z-.16],[3.84,z-.09],[3.84,z+.09],[3.76,z+.16]].map(([r,zz])=>circle(x,18.5,r,zz)),1,false);
  loft(g,'collar_metal_edge',[[3.835,z+.07],[3.855,z+.1]].map(([r,zz])=>circle(x,18.5,r,zz)),2,false);
 }
 for(let i=0;i<12;i++){
  const a=i*Math.PI/6;
  const edgeRadius=3.47*Math.cos(Math.PI/6)/Math.cos(a-(Math.floor(a/(Math.PI/3))+.5)*Math.PI/3);
  fastener(g,'front_endplate_lock',[x+Math.cos(a)*edgeRadius,18.5+Math.sin(a)*edgeRadius,-10.825],[0,0,-1],.085);
  fastener(g,'rear_endplate_lock',[x+Math.cos(a)*edgeRadius,18.5+Math.sin(a)*edgeRadius,10.23],[0,0,1],.085);
 }
 for(const s of [-1,1]){
  for(let i=0;i<9;i++)patch('curved_radiator_louver',s*4.8,s*6.2,6.2+i*.31,6.35+i*.31,3.83,2,8);
  for(const z of [-7.7,-1,4.8]){const a=s*5.65/3.75;fastener(g,'side_panel_lock',skin(s*5.65,z,3.8),[Math.sin(a),Math.cos(a),0],.105);}
  patch('status_recess',s*3.9,s*4.17,-4.4,-2.2,3.82,0,4);
  for(let i=0;i<3;i++)patch('violet_status_segment',s*3.96,s*4.1,-4.15+i*.61,-3.8+i*.61,3.84,6,3);
 }
 for(const u of [-2.5,2.5])for(const z of [-8.3,-2.5,3.3,9.1]){
  const a=u/3.75;fastener(g,'panel_edge_screw',skin(u,z,3.85),[Math.sin(a),Math.cos(a),0],.09);
 }
 // Recoil guides, trunnion saddle and flex cable are attached to this moving cassette.
 for(const dx of [-1.3,1.3])tube(g,'underside_recoil_guide',[x+dx,14.6,-5.6],[x+dx,14.6,5.7],.2,2,16);
 tube(g,'trunnion_saddle',[x-2.1,14.9,0],[x+2.1,14.9,0],1.25,1,32);
 for(const z of [-4.8,4.5])tube(g,'recoil_support_pin',[x-2,14.6,z],[x+2,14.6,z],.3,1,16);
 arcCable(g,'launcher_power_flex',[x,15.3,7.4],[x+side*2.5,12.65,5.1],[x,14.3,1.5],.18);
 const positions=[[x,18.5],...Array.from({length:6},(_,i)=>{const a=Math.PI/6+i*Math.PI/3;return[x+Math.cos(a)*1.88,18.5+Math.sin(a)*1.88];})];
 for(let j=0;j<positions.length;j++){
  const [rx,y]=positions[j];
  const circles=[[.86,-10.86],[.94,-10.99],[.88,-11.17],[.59,-11.21],[.59,-10.86]].map(([r,z])=>circle(rx,y,r,z,32));
  loft(g,'hexagonal_launch_socket_'+j,circles,2,false);
  loft(g,'socket_inner_gasket_'+j,[[.65,-11.16],[.65,-11.22],[.57,-11.22],[.57,-11.12]].map(([r,z])=>circle(rx,y,r,z,32)),0,false);
  for(let k=0;k<3;k++){const a=k*2*Math.PI/3;fastener(g,'launch_cell_lock',[rx+Math.cos(a)*.8,y+Math.sin(a)*.8,-11.18],[0,0,-1],.065);}
  loft(g,'recessed_javelin_shank_'+j,[[.48,-10.65],[.48,-11.26]].map(([r,z])=>Array.from({length:24},(_,i)=>[rx+Math.cos(i*Math.PI/12)*r,y+Math.sin(i*Math.PI/12)*r,z])),1);
  loft(g,'short_conical_warhead_'+j,[[.59,-11.22],[.62,-11.47],[.49,-12.27],[.27,-13.52],[0,-15.07]].map(([r,z])=>Array.from({length:24},(_,i)=>[rx+Math.cos(i*Math.PI/12)*r,y+Math.sin(i*Math.PI/12)*r,z])),4);
  loft(g,'rocket_base_band_'+j,[[.622,-11.48],[.615,-11.61]].map(([r,z])=>circle(rx,y,r,z,32)),2,false);
  for(let k=0;k<3;k++){
   const a=k*2*Math.PI/3;
   mesh(g,'recessed_socket_guide',[[rx+Math.cos(a)*.5,y+Math.sin(a)*.5,-10.88],[rx+Math.cos(a)*.76,y+Math.sin(a)*.76,-11.08],[rx+Math.cos(a)*.5,y+Math.sin(a)*.5,-11.48]],[[0,1,2]],2);
  }
 }
}
// Eight material strips: powder-coated black, graphite, brushed metal, and violet finishes.
const palette=['17131e','28222f','655a70','392449','582b78','793e9f','a064cf','bb8cd7'];
const W=256,H=256,raw=Buffer.alloc((W*4+1)*H);
for(let y=0;y<H;y++)for(let x=0;x<W;x++){
 const mat=Math.floor(x/32),u=x%32,seed=((x*73856093)^(y*19349663))>>>0;
 let delta=((seed%101)/100-.5)*(mat===2?9:5);
 if(mat===2)delta+=Math.sin(y*2.4)*3.5;
 if(mat<3&&((seed%607===0)||(u===3&&y%37<9)))delta+=15;
 if(mat>=3)delta+=Math.sin(x*.3+y*.04)*1.4;
 if(u<2||u>29)delta-=5;
 const p=y*(W*4+1)+1+x*4;for(let c=0;c<3;c++)raw[p+c]=Math.max(0,Math.min(255,parseInt(palette[mat].slice(c*2,c*2+2),16)+delta));raw[p+3]=255;
}
function crc(b){let c=0xffffffff;for(const v of b){c^=v;for(let i=0;i<8;i++)c=(c>>>1)^((c&1)?0xedb88320:0);}return(c^0xffffffff)>>>0;}
function chunk(type,data){const t=Buffer.from(type),l=Buffer.alloc(4),c=Buffer.alloc(4);l.writeUInt32BE(data.length);c.writeUInt32BE(crc(Buffer.concat([t,data])));return Buffer.concat([l,t,data,c]);}
const ih=Buffer.alloc(13);ih.writeUInt32BE(W);ih.writeUInt32BE(H,4);ih[8]=8;ih[9]=6;
const png=Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ih),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);
fs.writeFileSync('artifacts/blockbench/violet_turret/violet_palette.png',png);
Object.assign(model.textures[0],{source:'data:image/png;base64,'+png.toString('base64'),width:W,height:H,uv_width:W,uv_height:H});
model.resolution={width:W,height:H};
model.elements=elements;model.outliner=root;model.name='Violet Javelin Turret — Detailed Hexagonal';
const bones=new Set();function collect(gs){for(const g of gs)if(typeof g!=='string'){bones.add(g.uuid);collect(g.children);}}collect(root);
for(const a of model.animations)for(const uuid of Object.keys(a.animators))if(!bones.has(uuid))throw Error('Animation bone missing');
for(const e of elements)for(const f of Object.values(e.faces))for(const v of f.vertices)if(!e.vertices[v]||!f.uv[v])throw Error('Invalid mesh face');
for(const launcher of [old.launcher_left,old.launcher_right]){
 const newGroup=root[0].children.find(c=>c.name==='yaw').children.find(c=>c.name==='pitch').children.find(c=>c.uuid===launcher.uuid);
 const warheads=elements.filter(e=>newGroup.children.includes(e.uuid)&&e.name.startsWith('short_conical_warhead_'));
 if(warheads.length!==7)throw Error('Launcher must contain exactly seven rockets');
}
fs.writeFileSync(file,JSON.stringify(model,null,2));console.log(`PASS: ${elements.length} sculpted mesh elements, ${model.animations.length} animations; ${file}`);
