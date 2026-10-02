// RF shield generator rebuilt from the author's floating ring / three runic vanes reference.
// The vanes contain actual through-holes. Geometry is authored here; no game mesh is imported.
export function buildRfGenerator(Mesh,{add,sub,mul,norm,cross}) {
  const m=new Mesh("rf_ship_shield_generator",{
    hull:[[.075,.16,.20],0,"relics_addon:block/ship/machined"],
    inset:[[.025,.065,.085],0,"relics_addon:block/ship/enamel"],
    edge:[[.20,.32,.37],0,"relics_addon:block/ship/aged_alloy"],
    groove:[[.035,.09,.12],0],
    glow:[[.20,.90,.89],.85],
    white:[[.60,1,.97],1],
    core:[[.60,.98,.95],.95,"relics_addon:block/ship/crystal"],
  });
  const Y=[0,1,0], centre=[.5,.78,.5];
  // A tiered stone-metal ritual platform, with a recessed face and segmented outer rim.
  m.lathe("body",j=>j===1||j===3?"edge":j>5?"inset":"hull",[.5,0,.5],Y,
    [[0,0],[.46,0],[.47,.028],[.45,.035],[.45,.055],[.425,.067],[.375,.071],[0,.071]],64,[1,1]);
  for(let i=0;i<18;i++) {
    const a=i*Math.PI/9, b=a+.22, r=.449;
    const path=Array.from({length:5},(_,j)=>[.5+Math.cos(a+(b-a)*j/4)*r,.048,.5+Math.sin(a+(b-a)*j/4)*r]);
    m.ribbon("body",path,path.map(()=>Y),.014,.008,"edge","hull");
  }
  m.annulus("ring","glow",[.5,.072,.5],Y,.346,.350,64);
  m.torus("body","edge",[.5,.073,.5],Y,.389,.006,64,6);
  // Engraved constellation diagram, intersecting spokes, contact wells and short light columns.
  const nodes=Array.from({length:6},(_,i)=>{
    const a=i*Math.PI/3+.14;
    return [.5+Math.cos(a)*.28,.074,.5+Math.sin(a)*.28];
  });
  for(let i=0;i<6;i++) {
    const p=nodes[i];
    m.tube("ring","glow",p,nodes[(i+2)%6],.0018,4);
    m.tube("ring","glow",p,[.5,.074,.5],.0012,4);
    m.lathe("body","edge",p,Y,[[0,0],[.021,0],[.021,.006],[.014,.008],[0,.008]],12);
    m.lens("fx","white",add(p,[0,.009,0]),Y,.011,.003,2,12);
    m.tube("fx","glow",add(p,[0,.012,0]),add(p,[0,.075,0]),.002,6);
    m.annulus("ring","glow",add(p,[0,.010,0]),Y,.019,.021,16);
  }
  // The floating assembly has two concentric interrupted rails and a slim open central aperture.
  for(const y of [-.038,.038]) {
    m.torus("core","hull",add(centre,[0,y,0]),Y,.327,.020,64,8);
    m.torus("core","edge",add(centre,[0,y+.014,0]),Y,.332,.005,64,6);
    m.annulus("core","glow",add(centre,[0,y+.020,0]),Y,.302,.315,64);
  }
  // Six bridge assemblies, mechanical seats and luminous engraved traces across the ring.
  for(let i=0;i<6;i++) {
    const a=i*Math.PI/3+.12, d=[Math.cos(a),0,Math.sin(a)], t=[-Math.sin(a),0,Math.cos(a)];
    const seat=add(centre,mul(d,.335));
    m.panel("core","hull","edge","inset",add(seat,[0,-.028,0]),t,Y,d,.035,.046,.026,.007);
    const p=add(centre,mul(d,.265));
    m.lathe("core",j=>j===1?"edge":"hull",p,d,[[0,0],[.020,0],[.022,.020],[.017,.035],[.017,.070],[.022,.080],[0,.085]],10);
    m.torus("core","glow",add(p,mul(d,.044)),d,.018,.0025,12,5);
    for(const y of [-.02,.02]) {
      const path=Array.from({length:6},(_,j)=>{
        const f=j/5,angle=a-.26+f*.39;
        return add(centre,[Math.cos(angle)*(.339+(j%2)*.007),y,Math.sin(angle)*(.339+(j%2)*.007)]);
      });
      m.cable("core","glow",path,.0016,4);
    }
  }
  // A bright spherical source remains exposed in the aperture.
  m.sphere("fx","core",centre,.098,14,24);
  m.sphere("fx","white",centre,.083,10,16);
  const phi=(1+Math.sqrt(5))/2, ico=[];
  for(const [a,b] of [[1,phi],[1,-phi],[-1,phi],[-1,-phi]]) ico.push(norm([0,a,b]),norm([a,b,0]),norm([b,0,a]));
  for(let i=0;i<ico.length;i++) for(let j=i+1;j<ico.length;j++) if(Math.hypot(...sub(ico[i],ico[j]))<1.1) {
    m.tube("fx","white",add(centre,mul(ico[i],.099)),add(centre,mul(ico[j],.099)),.0014,4);
  }
  // Three asymmetric floating vanes: swept triangular outlines with a real circular aperture.
  const heights=[.62,1.12,.97];
  for(let index=0;index<3;index++) {
    const group=`shell_${index}`, angle=-Math.PI/2+index*Math.PI*2/3;
    const n=[Math.cos(angle),0,Math.sin(angle)], u=[-Math.sin(angle),0,Math.cos(angle)];
    const pivot=add([.5,heights[index],.5],mul(n,.395));
    const outer=[], count=36, control=[[-.19,-.16],[-.065,.24],[.185,-.14]];
    for(let edge=0;edge<3;edge++) {
      const a=control[edge],b=control[(edge+1)%3];
      for(let k=0;k<12;k++) {
        const f=k/12, bend=edge===2?-.065:.024;
        outer.push([a[0]*(1-f)+b[0]*f, a[1]*(1-f)+b[1]*f+bend*Math.sin(f*Math.PI)]);
      }
    }
    const hole=[0,-.085], radius=.038;
    const warp=(p,back=0)=>add(add(add(pivot,mul(u,p[0])),mul(Y,p[1])),mul(n,.016+.065*(p[0]/.19)**2+back));
    const normal=p=>norm(add(n,mul(u,-2*.065*p[0]/(.19*.19))));
    const inner=outer.map(p=>{
      const a=Math.atan2(p[1]-hole[1],p[0]-hole[0]);
      return [hole[0]+Math.cos(a)*radius,hole[1]+Math.sin(a)*radius];
    });
    for(let i=0;i<count;i++) {
      const j=(i+1)%count, pts=[inner[i],outer[i],outer[j],inner[j]];
      m.quad(group,"hull",pts.map(p=>warp(p)),pts.map(normal),pts.map(p=>[(p[0]+.2)/.4,(p[1]+.24)/.5]));
      m.quad(group,"inset",pts.map(p=>warp(p,-.012)),pts.map(p=>mul(normal(p),-1)));
      m.poly(group,"edge",[warp(outer[i]),warp(outer[j]),warp(outer[j],-.012),warp(outer[i],-.012)]);
      m.poly(group,"edge",[warp(inner[j]),warp(inner[i]),warp(inner[i],-.012),warp(inner[j],-.012)]);
    }
    const loop=points=>[...points,points[0]].map(p=>warp(p,.003));
    m.cable(group,"edge",loop(outer),.004,5);
    const rim=outer.map(p=>[p[0]*.88,p[1]*.88]);
    m.cable(group,"glow",loop(rim),.0015,4);
    m.cable(group,"glow",loop(inner),.0018,4);
    // Routed branching sigils follow the vane surface without closing its aperture.
    const strokes=[[[0,.18],[-.04,.11],[-.07,.06],[-.09,-.02],[-.12,-.10]],
      [[-.04,.11],[.012,.065],[.035,.005],[.067,-.04],[.12,-.10]],
      [[-.07,.06],[-.035,.022],[-.015,-.025]],
      [[.035,.005],[.072,.025],[.107,-.02],[.14,-.11]],
      [[-.14,-.14],[-.075,-.155],[0,-.145],[.09,-.155],[.15,-.14]]];
    for(const depth of [.004,-.016]) {
      for(const stroke of strokes) m.cable(group,"glow",stroke.map(p=>warp(p,depth)),.0015,4);
      m.cable(group,"glow",[...rim,rim[0]].map(p=>warp(p,depth)),.0015,4);
    }
    for(const [x,y] of [[-.068,.13],[-.145,-.12],[.14,-.11]]) {
      const diamond=[[x,y+.012],[x-.009,y],[x,y-.012],[x+.009,y],[x,y+.012]];
      m.cable(group,"glow",diamond.map(p=>warp(p,.004)),.0014,4);
    }
  }
  m.write(["body","ring","core","fx","shell_0","shell_1","shell_2"]);
  return {shells:3,height:1.4};
}
