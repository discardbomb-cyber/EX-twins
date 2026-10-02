import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import zlib from 'node:zlib';

const out = path.resolve('artifacts/blockbench/violet_turret');
fs.mkdirSync(out, {recursive:true});
const id = () => crypto.randomUUID();
const palette=['131018','211b29','494050','302040','492465','693397','9455c0','be83e3'];
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
fs.writeFileSync(path.join(out,'violet_palette.png'),png);
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
const base=group('base',[0,0,0]);
radial(base,'circular_foundation',[[0,0],[13.4,0],[14,1],[14,2.3],[13.5,2.8],[0,2.8]],0);
radial(base,'outer_beveled_rim',[[12.2,2.8],[13.5,2.8],[13.7,3.1],[13.5,3.5],[12.2,3.5],[12.2,2.8]],2);
radial(base,'violet_ring_inlay',[[12.35,3.51],[13.1,3.51]],6);
radial(base,'inner_dark_plate',[[0,2.82],[12.15,2.82]],1);
radial(base,'outer_lower_trim',[[13.91,.8],[14.06,.9],[14.06,1.14],[13.99,1.22]],2);
radial(base,'inner_maintenance_track',[[9.65,2.84],[9.83,2.84]],2);
for(let i=0;i<24;i++){
 const a=i*Math.PI/12,x=Math.sin(a)*13.35,z=Math.cos(a)*13.35;
 bolt(base,'rim_fastener_'+i,[x,3.53,z],[0,1,0],.13);
 const r0=10.0,r1=12.1,w=.055;
 mesh(base,'deck_radial_seam',[[Math.sin(a-w)*r0,2.85,Math.cos(a-w)*r0],[Math.sin(a+w)*r0,2.85,Math.cos(a+w)*r0],[Math.sin(a+w*.6)*r1,2.85,Math.cos(a+w*.6)*r1],[Math.sin(a-w*.6)*r1,2.85,Math.cos(a-w*.6)*r1]],[[0,1,2,3]],0);
}
for(let i=0;i<4;i++){
 extrusion(base,'rounded_foot_'+i,[[-4.1,11],[-4.1,18.2],[-3.3,20],[3.3,20],[4.1,18.2],[4.1,11]],.2,2.1,0,45+i*90);
 extrusion(base,'foot_bevel_'+i,[[-3.8,11.5],[-3.8,18],[-3,19.5],[3,19.5],[3.8,18],[3.8,11.5]],2.1,2.45,2,45+i*90);
 extrusion(base,'foot_purple_inset_'+i,[[-2.8,13],[-2.8,17.7],[-2.3,18.5],[2.3,18.5],[2.8,17.7],[2.8,13]],2.46,2.5,7,45+i*90);
 const a=(45+i*90)*Math.PI/180,turn=([x,y,z])=>[x*Math.cos(a)+z*Math.sin(a),y,-x*Math.sin(a)+z*Math.cos(a)];
 for(const x of [-3.28,3.28])for(const z of [12.5,17.7])bolt(base,'foot_anchor_'+i,turn([x,2.48,z]),[0,1,0],.19);
 for(const z of [14.25,16.9]){
  const ps=[[-2.8,2.51,z-.055],[2.8,2.51,z-.055],[2.8,2.51,z+.055],[-2.8,2.51,z+.055]];
  mesh(base,'foot_panel_divider',ps.map(turn),[[0,1,2,3]],4);
 }
 for(const x of [-3.6,3.6])tube(base,'foot_mount_piston',turn([x,1.5,11.4]),turn([x,1.5,15]),.22,2);
}
radial(base,'plinth',[[0,3],[7.8,3],[8.6,3.8],[8.6,4.5],[7.2,5.1],[0,5.1]],0,12);
radial(base,'sloped_pedestal',[[7.3,4.4],[5,9.2],[4.7,9.5],[0,9.5]],2,8);
for(let i=0;i<8;i++){
 const a=i*45;
 const ang=a*Math.PI/180,turn=([x,y,z])=>[x*Math.cos(ang)+z*Math.sin(ang),y,-x*Math.sin(ang)+z*Math.cos(ang)];
 const panel=[[-2.15,4.6,7.8],[2.15,4.6,7.8],[1.65,8.7,5.6],[-1.65,8.7,5.6],[-2.15,4.6,8.2],[2.15,4.6,8.2],[1.65,8.7,6],[-1.65,8.7,6]];
 mesh(base,'pedestal_armor_'+i,panel.map(turn),[[0,1,2,3],[4,7,6,5],[0,4,5,1],[3,2,6,7],[0,3,7,4],[1,5,6,2]],0);
 for(const side of [-1,1]){const rib=[ [side*1.7,4.9,8.25],[side*2.05,4.9,8.25],[side*1.55,8.3,6.15],[side*1.25,8.3,6.15] ];mesh(base,'pedestal_metal_rib',rib.map(turn),[[0,1,2,3]],2);}
 for(const side of [-1,1]){
  tube(base,'pedestal_actuator_body',turn([side*2.6,4.1,7.7]),turn([side*2.0,6.6,6.8]),.3,1);
  tube(base,'pedestal_actuator_rod',turn([side*2.0,6.6,6.8]),turn([side*1.45,8.9,5.6]),.13,2);
  tube(base,'actuator_lower_pin',turn([side*2.6-.4,4.1,7.7]),turn([side*2.6+.4,4.1,7.7]),.32,2);
 }
 for(let j=0;j<4;j++){
  const y=6.35+j*.35,z=8.2-(y-4.6)*.54;
  mesh(base,'pedestal_vent',[[ -1.05,y,z+.04],[1.05,y,z+.04],[1.05,y+.12,z-.025],[-1.05,y+.12,z-.025]].map(turn),[[0,1,2,3]],2);
 }
 box(base,'pedestal_power_'+i,[-1.65,4.5,7],[1.65,5.6,7.4],4,[0,a,0],[0,5,0]);
 for(let j=0;j<3;j++)box(base,'cooling_fin_'+i+'_'+j,[-1.6,5+j*.42,7.41],[1.6,5.18+j*.42,7.65],2,[0,a,0],[0,5,0]);
}
radial(base,'bearing_rim',[[0,9.4],[5,9.4],[5.5,9.7],[5.5,10.1],[4.8,10.3],[0,10.3]],1,32);
for(let i=0;i<32;i++){const a=i*Math.PI/16;box(base,'bearing_tooth_'+i,[-.22,9.75,5.3],[.22,10.0,5.75],2,[0,i*11.25,0],[0,0,0]);}
const yaw=group('yaw',[0,10,0],base);
radial(yaw,'yaw_column',[[0,10.1],[3.5,10.1],[3.8,10.7],[3.3,11.3],[3.1,14.4],[2.6,15.2],[0,15.2]],0,24);
radial(yaw,'column_band',[[3.34,11.4],[3.34,11.8]],3,24);
for(let i=0;i<8;i++){const a=i*Math.PI/4;bolt(yaw,'hub_bolt_'+i,[Math.sin(a)*3.45,10.75,Math.cos(a)*3.45],[0,1,0],.16);}
for(const side of [-1,1]){
 tube(yaw,'elevation_motor',[side*2.7,13,0],[side*4.3,13,0],1.05,1,24);
 tube(yaw,'elevation_motor_cap',[side*4.3,13,0],[side*4.48,13,0],.8,2,24);
 tube(yaw,'hydraulic_aim_piston',[side*2.5,11.9,2],[side*2.5,14.2,-1],.32,2);
 tube(yaw,'hydraulic_aim_rod',[side*2.5,14.2,-1],[side*2.5,15.5,-2.8],.14,6);
}
const pitch=group('pitch',[0,16,0],yaw);
box(pitch,'cross_axle',[-9,14.5,-1.8],[9,17.5,1.8],1);
function cassette(g,x){
 const p=[[-3.45,15.4],[-4.1,16.2],[-4.1,21.1],[-3.15,22.25],[3.15,22.25],[4.1,21.1],[4.1,16.2],[3.45,15.4]];
 const pts=[];for(const z of [-10,11])for(const [u,y]of p)pts.push([x+u,y,z]);
 const faces=[Array.from({length:8},(_,i)=>7-i),Array.from({length:8},(_,i)=>8+i)];for(let i=0;i<8;i++)faces.push([i,(i+1)%8,(i+1)%8+8,i+8]);mesh(g,'beveled_cassette',pts,faces,0);
 box(g,'side_armor',[x-4.16,17,-8.8],[x+4.16,20,9.8],2);
 box(g,'side_recess',[x-4.18,18,-7.5],[x+4.18,18.35,8.7],1);
 // Layered side panels, accessible fasteners and rear radiator.
 for(const side of [-1,1]){
  const sx=x+side*4.19;
  for(let section=0;section<3;section++){
   const z=-8.2+section*5.7;
   mesh(g,'recessed_side_panel_'+section,[[sx,17.3,z],[sx,17.3,z+5.3],[sx,19.65,z+5.3],[sx,19.65,z]],[[0,1,2,3]],1);
   mesh(g,'side_panel_chamfer',[[sx+side*.025,19.5,z+.15],[sx+side*.025,19.5,z+5.1],[sx+side*.025,19.64,z+4.9],[sx+side*.025,19.64,z+.35]],[[0,1,2,3]],2);
   for(const dz of [.35,4.95])for(const yy of [17.6,19.3])bolt(g,'side_captive_bolt',[sx,yy,z+dz],[side,0,0],.115);
  }
  for(let k=0;k<8;k++)box(g,'rear_side_vent',[Math.min(sx,sx+side*.08),17.55,6.1+k*.38],[Math.max(sx,sx+side*.08),19.3,6.25+k*.38],2);
  box(g,'violet_status_bar',[Math.min(sx,sx+side*.06),18.0,-5.8],[Math.max(sx,sx+side*.06),18.22,-2.7],5);
 }
 box(g,'front_armored_surround',[x-1.7,15.85,-10.15],[x+1.7,21.75,-10.02],1);
 for(const dx of [-2.75,2.75]){
  box(g,'front_brace',[x+dx-.18,16.3,-10.23],[x+dx+.18,21.1,-10.02],2);
  for(const yy of [16.65,20.75])bolt(g,'face_bolt',[x+dx,yy,-10.25],[0,0,-1],.17);
 }
 box(g,'rear_radiator_back',[x-2.8,16.2,11.02],[x+2.8,21.2,11.15],1);
 for(let k=0;k<10;k++)box(g,'rear_radiator_fin',[x-2.7,16.45+k*.45,11.16],[x+2.7,16.62+k*.45,11.42],2);
 tube(g,'underside_recoil_piston',[x,14.85,-7],[x,14.85,5],.4,1,16);
 tube(g,'underside_recoil_rod',[x,14.85,-9.7],[x,14.85,-7],.19,2);
 for(const z of [-7,4.6])box(g,'recoil_mount',[x-1,14.45,z-.35],[x+1,15.4,z+.35],2);
 box(g,'top_violet_panel',[x-3.05,22.26,-9.2],[x+3.05,22.28,10.1],3);
 box(g,'left_frame',[x-3.4,22.28,-9.8],[x-3.05,22.6,10.7],2);
 box(g,'right_frame',[x+3.05,22.28,-9.8],[x+3.4,22.6,10.7],2);
 box(g,'front_frame',[x-3.4,22.28,-9.8],[x+3.4,22.6,-9.3],2);
 box(g,'rear_frame',[x-3.4,22.28,10.1],[x+3.4,22.6,10.7],2);
 for(const dx of [-3.23,3.23])for(const z of [-8.7,-3,3,9.5])bolt(g,'top_frame_screw',[x+dx,22.62,z],[0,1,0],.11);
 for(let row=0;row<13;row++)for(let col=0;col<4;col++){
  const cx=x-2.6+col*1.73+(row%2?.865:0),cz=-8.25+row*1.5,r=.99;
  if(cx+r>x+2.97||cz+r>10.05)continue;
  const poly=Array.from({length:6},(_,i)=>[cx+Math.sin(i*Math.PI/3)*r,cz+Math.cos(i*Math.PI/3)*r]);
  extrusion(g,'hex_cell_'+row+'_'+col,poly,22.29,22.32,(row+col)%3===0?4:3);
  for(let i=0;i<6;i++){const a=poly[i],b=poly[(i+1)%6];const dx=b[0]-a[0],dz=b[1]-a[1],l=Math.hypot(dx,dz),w=.035;
   mesh(g,'hex_seam',[[a[0]-dz/l*w,22.34,a[1]+dx/l*w],[b[0]-dz/l*w,22.34,b[1]+dx/l*w],[b[0]+dz/l*w,22.34,b[1]-dx/l*w],[a[0]+dz/l*w,22.34,a[1]-dx/l*w]],[[0,1,2,3]],1);
  }
 }
 for(let j=0;j<4;j++){
  const y=16.4+j*1.55,n=12,pts=[],f=[];
  for(const [r,z]of [[.68,-10.04],[.8,-10.4],[.68,-10.8],[.46,-10.85],[.46,-10.1]])for(let k=0;k<n;k++){let a=k*2*Math.PI/n;pts.push([x+Math.cos(a)*r,y+Math.sin(a)*r,z]);}
  for(let t=0;t<4;t++)for(let k=0;k<n;k++)f.push([t*n+k,t*n+(k+1)%n,(t+1)*n+(k+1)%n,(t+1)*n+k]);mesh(g,'metal_launch_socket_'+j,pts,f,2);
  for(let k=0;k<4;k++){
   const a=k*Math.PI/2+Math.PI/4;
   bolt(g,'socket_locking_bolt',[x+Math.cos(a)*.63,y+Math.sin(a)*.63,-10.83],[0,0,-1],.085);
  }
  tube(g,'dart_rear_collar',[x,y,-11.05],[x,y,-11.2],.51,2,12);
  const dart=[];for(const [r,z]of [[.42,-10.6],[.5,-12.1],[.32,-14.3],[0,-16.3]])for(let k=0;k<8;k++){const a=k*Math.PI/4;dart.push([x+Math.cos(a)*r,y+Math.sin(a)*r,z]);}
  const df=[];for(let t=0;t<3;t++)for(let k=0;k<8;k++)df.push([t*8+k,t*8+(k+1)%8,(t+1)*8+(k+1)%8,(t+1)*8+k]);mesh(g,'pointed_javelin_'+j,dart,df,4);
  for(let k=0;k<3;k++){const a=k*2*Math.PI/3,dx=Math.cos(a),dy=Math.sin(a);mesh(g,'javelin_fin',[[x+dx*.35,y+dy*.35,-10.8],[x+dx*.95,y+dy*.95,-11.2],[x+dx*.35,y+dy*.35,-12.8]],[[0,1,2]],2);}
 }
}
for(const side of [-1,1]){const x=side*6;const g=group(side<0?'launcher_left':'launcher_right',[x,17,0],pitch);cassette(g,x);}
/* Legacy blockout retained below for comparison, disabled. */
if(false){
const base=group('base',[0,0,0]);
for(let i=0;i<12;i++){let a=i*30,r=a*Math.PI/180,x=Math.sin(r)*12,z=Math.cos(r)*12;
 box(base,'ring_segment_'+i,[x-3.4,1,z-1.2],[x+3.4,3,z+1.2],1,[0,a,0],[x,2,z]);
 box(base,'violet_ring_'+i,[x-3.15,3.02,z-.42],[x+3.15,3.3,z+.42],5,[0,a,0],[x,3,z]);}
for(let i=0;i<4;i++){const a=45+i*90;
 box(base,'outrigger_'+i,[-4,0,9],[4,2.2,21],0,[0,a,0],[0,0,0]);
 box(base,'foot_panel_'+i,[-3.1,2.21,13],[3.1,2.5,19.8],3,[0,a,0],[0,0,0]);
 box(base,'foot_light_'+i,[-2.8,2.51,18.5],[2.8,2.7,19.1],6,[0,a,0],[0,0,0]);}
box(base,'foundation',[-7,1,-7],[7,4,7],0);
for(let i=0;i<8;i++)box(base,'pedestal_armor_'+i,[-3,3,4.8],[3,8,7.3],2,[0,i*45,0],[0,5,0]);
box(base,'bearing',[-5,7,-5],[5,9,5],0);
const yaw=group('yaw',[0,9,0],base);
box(yaw,'rotation_hub',[-4,8,-4],[4,12,4],2);
box(yaw,'hub_band',[-4.2,9.5,-4.2],[4.2,10.3,4.2],4);
box(yaw,'neck',[-2.6,11,-2.6],[2.6,16,2.6],0);
const pitch=group('pitch',[0,16,0],yaw);
box(pitch,'cross_axle',[-10,14,-2],[10,18,2],2);
for(const side of [-1,1]){
 const x=side*6.5,g=group(side<0?'launcher_left':'launcher_right',[x,17,0],pitch);
 box(g,'cassette_shell',[x-4,15,-10],[x+4,22,10],0);
 box(g,'side_armor',[x-4.15,16,-8.8],[x+4.15,20.5,9],2);
 box(g,'top_inset',[x-3.4,22.02,-9],[x+3.4,22.25,9],3);
 box(g,'top_rail_left',[x-3.85,22,-10],[x-3.35,22.65,10],1);
 box(g,'top_rail_right',[x+3.35,22,-10],[x+3.85,22.65,10],1);
 for(let j=0;j<9;j++){
  const z=-8+j*2;
  box(g,'panel_tile_'+j,[x-2.8,22.26,z-.72],[x+2.8,22.43,z+.72],j%2?3:4);
  box(g,'panel_seam_'+j,[x-.13,22.44,z-.72],[x+.13,22.48,z+.72],1);
 }
 box(g,'rear_cap',[x-3.7,15.5,9.7],[x+3.7,21.5,10.5],1);
 box(g,'side_power_strip',[x-4.2,18,-7.5],[x+4.2,18.5,8],5);
 for(let j=0;j<4;j++){
  const y=16+j*1.55;
  box(g,'launch_socket_'+j,[x-2.9,y-.56,-10.6],[x+2.9,y+.56,-9.7],2);
  box(g,'socket_core_'+j,[x-1.3,y-.42,-11],[x+1.3,y+.42,-10.5],0);
  box(g,'dart_'+j,[x-.65,y-.36,-14.2],[x+.65,y+.36,-10.7],4);
  box(g,'dart_tip_'+j,[x-.3,y-.2,-15.5],[x+.3,y+.2,-14.1],6);
  box(g,'dart_fin_'+j,[x-1.3,y-.1,-12.3],[x+1.3,y+.1,-11.2],3);
 }
}
}
const animations=[];
function anim(name,length,loop,tracks){const animators={};for(const [g,channel,frames] of tracks){const a=animators[g.uuid]??={name:g.name,type:'bone',keyframes:[]};for(const [time,v]of frames)a.keyframes.push({uuid:id(),channel,time,interpolation:'linear',data_points:[{x:String(v[0]),y:String(v[1]),z:String(v[2])}]});}animations.push({uuid:id(),name,loop,override:false,length,snapping:24,anim_time_update:'',blend_weight:'',start_delay:'',loop_delay:'',animators});}
const left=pitch.children.find(g=>g.name==='launcher_left'),right=pitch.children.find(g=>g.name==='launcher_right');
anim('idle',4,'loop',[[pitch,'rotation',[[0,[0,0,0]],[2,[-2,0,0]],[4,[0,0,0]]]]]);
anim('scan',6,'loop',[[yaw,'rotation',[[0,[0,-55,0]],[3,[0,55,0]],[6,[0,-55,0]]]],[pitch,'rotation',[[0,[-8,0,0]],[3,[5,0,0]],[6,[-8,0,0]]]]]);
anim('aim',.6,'hold',[[pitch,'rotation',[[0,[0,0,0]],[.6,[-25,0,0]]]]]);
anim('fire',.65,'once',[[left,'position',[[0,[0,0,0]],[.08,[0,0,1.5]],[.3,[0,0,0]],[.65,[0,0,0]]]],[right,'position',[[0,[0,0,0]],[.2,[0,0,0]],[.28,[0,0,1.5]],[.55,[0,0,0]],[.65,[0,0,0]]]],[pitch,'rotation',[[0,[0,0,0]],[.08,[-3,0,0]],[.2,[0,0,0]],[.28,[-3,0,0]],[.65,[0,0,0]]]]]);
anim('reload',1.8,'once',[[left,'position',[[0,[0,0,0]],[.5,[-1,0,1]],[1.2,[-1,0,1]],[1.8,[0,0,0]]]],[right,'position',[[0,[0,0,0]],[.5,[1,0,1]],[1.2,[1,0,1]],[1.8,[0,0,0]]]]]);
const model={meta:{format_version:'4.10',model_format:'free',box_uv:false},name:'Violet Javelin Turret',model_identifier:'violet_javelin_turret',visible_box:[5,4,0],resolution:{width:W,height:H},elements,outliner:groups,textures:[{name:'violet_palette.png',id:'0',uuid:id(),source:'data:image/png;base64,'+png.toString('base64'),width:W,height:H,uv_width:W,uv_height:H,particle:false,render_mode:'default',render_sides:'double',visible:true,internal:true,saved:true}],animations};
const target=path.join(out,'violet_javelin_turret.bbmodel');fs.writeFileSync(target,JSON.stringify(model,null,2));
const parsed=JSON.parse(fs.readFileSync(target));const ids=new Set(parsed.elements.map(e=>e.uuid));
const boneIds=new Set(),linked=new Set();
function check(gs){for(const g of gs){if(typeof g==='string'){if(!ids.has(g))throw Error('Missing element');if(linked.has(g))throw Error('Duplicate element reference');linked.add(g);}else{boneIds.add(g.uuid);check(g.children);}}}check(parsed.outliner);
if(linked.size!==elements.length)throw Error('Unattached geometry');
for(const e of parsed.elements){
 if(e.type==='mesh'){
  for(const vertex of Object.values(e.vertices))if(vertex.length!==3||vertex.some(v=>!Number.isFinite(v)))throw Error('Invalid mesh coordinate');
  for(const face of Object.values(e.faces))for(const v of face.vertices)if(!e.vertices[v]||!face.uv[v])throw Error('Invalid mesh face');
 }else if(e.from.some((v,i)=>v>e.to[i]))throw Error('Inverted cube bounds');
}
for(const a of parsed.animations)for(const [boneId,bone] of Object.entries(a.animators)){
 if(!boneIds.has(boneId))throw Error('Missing animation bone');
 for(const k of bone.keyframes){if(k.time>a.length||k.time<0)throw Error('Animation duration');for(const p of k.data_points)if(['x','y','z'].some(c=>!Number.isFinite(Number(p[c]))))throw Error('Invalid keyframe');}
}
console.log(`PASS: ${elements.length} elements, ${animations.length} animations; embedded PNG ${W}x${H}.\n${target}`);
