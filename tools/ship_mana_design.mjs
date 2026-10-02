// Spherical mana reactor with three curved armoured petals, based on the author's new references.
// Coordinates and part pivots are shared with ShipDeviceRenderer; no gameplay state lives here.
export function buildManaGenerator(Mesh, {add,sub,mul,norm,cross,perp}) {
  const m=new Mesh("mana_ship_shield_generator",{
    ceramic:[[.79,.81,.78],0,"relics_addon:block/ship/enamel"],
    shadow:[[.41,.48,.55],0,"relics_addon:block/ship/machined"],
    navy:[[.024,.04,.09],0,"relics_addon:block/ship/enamel"],
    gold:[[.59,.40,.13],0,"relics_addon:block/ship/aged_alloy"],
    edge:[[.91,.71,.30],0,"relics_addon:block/ship/aged_alloy"],
    crystal:[[.045,.45,.83],.20,"relics_addon:block/ship/crystal"],
    light:[[.28,.89,1],.80],
    ice:[[.66,.92,1],.45],
  });
  const c=[.5,.62,.5], Y=[0,1,0];
  // Low circular foundation, cut radial sockets and a narrow activation ring.
  m.lathe("body",i=>i===2?"gold":i>3?"navy":"shadow",[.5,0,.5],Y,
    [[0,0],[.44,0],[.45,.025],[.43,.047],[.35,.06],[.30,.09],[.22,.11],[0,.11]],48,[1,1]);
  m.annulus("ring","light",[.5,.049,.5],Y,.395,.405,64);
  m.torus("body","edge",[.5,.065,.5],Y,.355,.005,48,6);
  for(let i=0;i<24;i++) {
    const a=i*Math.PI/12, d=[Math.cos(a),0,Math.sin(a)], t=[-Math.sin(a),0,Math.cos(a)];
    m.box("body",i%3===0?"gold":"navy",add([.5,.065,.5],mul(d,.383)),mul(d,.019),[0,.002,0],mul(t,.004));
  }
  // Three arched suspension struts with faceted contacts and inset conductors.
  for(let i=0;i<3;i++) {
    const a=i*Math.PI*2/3, d=[Math.cos(a),0,Math.sin(a)], t=[-Math.sin(a),0,Math.cos(a)];
    const path=Array.from({length:20},(_,j)=>{
      const f=j/19;
      return add([.5,.09+f*.235,.5],mul(d,.25-.10*f+.03*Math.sin(f*Math.PI)));
    });
    m.ribbon("body",path,path.map(()=>t),.048,.023,"ceramic","gold");
    m.cable("fx","ice",path.map(p=>add(p,mul(d,.014))),.002,5);
    m.lathe("body","gold",path.at(-1),Y,[[0,0],[.029,0],[.035,.009],[.029,.025],[0,.03]],12);
    m.lens("fx","light",add(path.at(-1),[0,.03,0]),Y,.016,.004,2,12);
  }
  // An exposed spherical core: a dark blue crystal, bright nodal net and a dark inner nucleus.
  m.sphere("core","navy",c,.092,10,16);
  m.torus("core","gold",c,Y,.102,.008,24,6);
  const phi=(1+Math.sqrt(5))/2, ico=[];
  for(const [a,b] of [[1,phi],[1,-phi],[-1,phi],[-1,-phi]]) ico.push(norm([0,a,b]),norm([a,b,0]),norm([b,0,a]));
  for(let i=0;i<ico.length;i++) for(let j=i+1;j<ico.length;j++) if(Math.hypot(...sub(ico[i],ico[j]))<1.1) {
    const path=Array.from({length:5},(_,k)=>add(c,mul(norm(add(mul(ico[i],1-k/4),mul(ico[j],k/4))),.216)));
    m.cable("core","ice",path,.0018,4);
    const inner=path.map(p=>add(c,mul(sub(p,c),.73)));
    m.cable("core","light",inner,.0012,4);
  }
  for(const p of ico) m.sphere("core","light",add(c,mul(p,.216)),.006,3,6);
  // A few cut crystal facets expose the nested lattice and the nucleus through large gaps.
  let face=0;
  for(let i=0;i<ico.length;i++) for(let j=i+1;j<ico.length;j++) for(let k=j+1;k<ico.length;k++) {
    if([sub(ico[i],ico[j]),sub(ico[j],ico[k]),sub(ico[k],ico[i])].some(v=>Math.hypot(...v)>1.1)) continue;
    if(face++%3===0) m.poly("core","crystal",[ico[i],ico[j],ico[k]].map(p=>add(c,mul(p,.205))));
  }
  // Each petal has a curved outer ceramic skin, inner navy backing, bevelled gold edge,
  // longitudinal carved channels, separate lens seat and radial fastening nodes.
  for(let i=0;i<3;i++) {
    const group=`shell_${i}`, angle=i*Math.PI*2/3;
    const dir=(f,q)=>{
      const theta=.47+f*2.20, width=.65*Math.pow(Math.sin(f*Math.PI),.68), a=angle+q*width;
      return [Math.sin(theta)*Math.cos(a),Math.cos(theta),Math.sin(theta)*Math.sin(a)];
    };
    const at=(f,q,r=.285)=>add(c,mul(dir(f,q),r));
    for(let row=0;row<22;row++) for(let col=0;col<10;col++) {
      const f=row/22,b=(row+1)/22,q=-1+col/5,p=-1+(col+1)/5;
      const corners=[[f,q],[b,q],[b,p],[f,p]], normals=corners.map(([v,t])=>dir(v,t));
      const uv=corners.map(([v,t])=>[(t+1)/2,v]);
      m.quad(group,col===0||col===9?"gold":"ceramic",corners.map(([v,t])=>at(v,t)),normals,uv);
      m.quad(group,"navy",corners.map(([v,t])=>at(v,t,.270)),normals.map(n=>mul(n,-1)),uv);
    }
    for(const side of [-1,1]) {
      const rim=Array.from({length:23},(_,j)=>at(j/22,side,.287));
      m.cable(group,"edge",rim,.004,5);
      for(const inner of [.60,.74]) {
        const trace=Array.from({length:21},(_,j)=>at(.055+j/20*.89,side*inner,.288));
        m.cable(group,inner===.60?"navy":"gold",trace,.002,4);
      }
    }
    const n=dir(.5,0), u=perp(n), w=cross(n,u), seat=at(.5,0,.29);
    m.panel(group,"navy","gold","shadow",seat,u,w,n,.064,.084,.006,.008);
    m.lens(group,"crystal",add(seat,mul(n,.012)),n,.046,.022,4,24);
    m.torus(group,"edge",add(seat,mul(n,.015)),n,.048,.004,24,6);
    const diamond=[[0,.027],[-.018,0],[0,-.027],[.018,0]].map(([x,y])=>add(add(add(seat,mul(u,x)),mul(w,y)),mul(n,.035)));
    for(let j=0;j<4;j++) m.tube(group,"light",diamond[j],diamond[(j+1)%4],.0015,4);
    for(let j=0;j<7;j++) for(const side of [-1,1]) {
      const f=.15+j*.105,p=at(f,side*.83,.29), n=dir(f,side*.83);
      m.lathe(group,"edge",p,n,[[0,0],[.005,0],[.005,.002],[0,.002]],6);
      if(j%2===0) m.lens(group,"light",add(p,mul(n,.003)),n,.003,.001,1,6);
    }
  }
  m.write(["body","ring","core","fx","shell_0","shell_1","shell_2"]);
  return {shells:3,height:1.05};
}
