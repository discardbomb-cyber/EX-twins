import fs from 'node:fs';
const dir='artifacts/blockbench/rf_workbench';
const source=JSON.parse(fs.readFileSync(`${dir}/rf_workbench.bbmodel`,'utf8'));
function rotate(p,o,r){let [x,y,z]=p.map((v,i)=>v-o[i]);for(let i=0;i<3;i++){const a=r[i]*Math.PI/180,c=Math.cos(a),s=Math.sin(a);if(i===0)[y,z]=[y*c-z*s,y*s+z*c];if(i===1)[x,z]=[x*c+z*s,-x*s+z*c];if(i===2)[x,y]=[x*c-y*s,x*s+y*c];}return [x+o[0],y+o[1],z+o[2]];}
for(const [clip,time,label] of [['uncharged',0,'uncharged'],['charged_open',0,'open'],['crafting',.75,'crafting']]){
 const model=structuredClone(source),animation=model.animations.find(a=>a.name===clip),chains=new Map();
 function walk(nodes,chain=[]){for(const g of nodes)if(typeof g==='string')chains.set(g,chain);else walk(g.children,[g,...chain]);}walk(model.outliner);
 function sample(g,channel){const keys=(animation.animators[g.uuid]?.keyframes??[]).filter(k=>k.channel===channel).sort((a,b)=>a.time-b.time);if(!keys.length)return channel==='scale'?[1,1,1]:[0,0,0];const left=keys.filter(k=>k.time<=time).at(-1)??keys[0],right=keys.find(k=>k.time>=time)??keys.at(-1),t=right.time===left.time?0:(time-left.time)/(right.time-left.time);return ['x','y','z'].map(c=>Number(left.data_points[0][c])*(1-t)+Number(right.data_points[0][c])*t);}
 for(const e of model.elements){if(e.type!=='mesh')continue;const chain=chains.get(e.uuid);for(const [key,vertex]of Object.entries(e.vertices)){let p=vertex;for(const g of chain){const s=sample(g,'scale'),r=sample(g,'rotation'),d=sample(g,'position');p=p.map((v,i)=>g.origin[i]+(v-g.origin[i])*s[i]);p=rotate(p,g.origin,r);p=p.map((v,i)=>v+d[i]);}e.vertices[key]=p;}}
 model.animations=[];fs.writeFileSync(`${dir}/preview_${label}.bbmodel`,JSON.stringify(model));
 console.log(`PASS baked ${clip} at ${time}s -> preview_${label}.bbmodel`);
}
