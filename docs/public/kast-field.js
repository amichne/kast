(() => {
/* Kast / 04 — a reactive character field. No runtime dependencies. */
const ALPHABET = "KAST";
const GRAPH = Object.freeze({
  nodes: Object.freeze([
    ["hub", 256, 270], ["a", 114, 235], ["b", 100, 360],
    ["c", 334, 388], ["d", 231, 164], ["e", 318, 122], ["f", 418, 166]
  ].map(Object.freeze)),
  edges: Object.freeze([[0,1],[1,2],[2,3],[3,0],[2,0],[0,4],[4,5],[5,6],[6,0],[5,0]].map(Object.freeze))
});
const PALETTES = Object.freeze({
  pale: Object.freeze({background:"#f1f2ef", glyph:"#69736d", edge:"#7a837d", strong:"#424d46"}),
  graphite: Object.freeze({background:"#242925", glyph:"#c5ccc5", edge:"#c0cac0", strong:"#edf0e9"})
});
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const hypot = Math.hypot;
const hash = (a,b) => {
  let n = Math.imul(a + 103, 374761393) ^ Math.imul(b + 97, 668265263);
  n = Math.imul(n ^ (n >>> 13), 1274126177);
  return ((n ^ (n >>> 16)) >>> 0) / 4294967296;
};
function onSegment(x,y,a,b) {
  const dx=b.x-a.x, dy=b.y-a.y, length2=dx*dx+dy*dy;
  const t=clamp(((x-a.x)*dx+(y-a.y)*dy)/(length2 || 1),0,1);
  const px=a.x+t*dx, py=a.y+t*dy;
  return {t,x:px,y:py,distance:hypot(x-px,y-py),length:Math.sqrt(length2)};
}

class FieldModel {
  constructor(width,height) {
    this.width=width; this.height=height;
    this.scale=Math.min(width*.76/318,height*.77/266);
    this.radius=clamp(width*.16,76,145);
    this.limit=clamp(width*.10,34,88);
    this.fontSize=width<500 ? 10 : 12;
    this.pointer={active:false,x:0,y:0};
    this.drag=null;
    const centerX=259, centerY=255;
    this.nodes=GRAPH.nodes.map(([id,x,y])=>({id,hx:width/2+(x-centerX)*this.scale,hy:height/2+(y-centerY)*this.scale,x:0,y:0,vx:0,vy:0}));
    this.nodes.forEach(n=>{n.x=n.hx;n.y=n.hy;});
    this.edges=GRAPH.edges.map(([a,b])=>({a,b,length:hypot(this.nodes[a].x-this.nodes[b].x,this.nodes[a].y-this.nodes[b].y)}));
    this.particles=[];
    this.buildField();
  }
  buildField() {
    const stepX=this.width<500 ? 11.1 : 13.7;
    const stepY=this.width<500 ? 14.5 : 17.4;
    const cols=Math.floor(this.width/stepX),rows=Math.floor(this.height/stepY);
    const left=(this.width-(cols-1)*stepX)/2,top=(this.height-(rows-1)*stepY)/2;
    for(let row=0;row<rows;row++) for(let col=0;col<cols;col++) {
      const x=left+col*stepX,y=top+row*stepY;
      const radial=Math.pow((x-this.width/2)/(this.width*.57),2)+Math.pow((y-this.height/2)/(this.height*.61),2);
      const fieldFade=Math.pow(clamp(1-radial*.62,0,1),1.4);
      if(fieldFade<.025) continue;
      let nearest={distance:Infinity,index:0,t:0},nodeDistance=Infinity,nodeIndex=0;
      this.edges.forEach((edge,index)=>{
        const p=onSegment(x,y,this.nodes[edge.a],this.nodes[edge.b]);
        if(p.distance<nearest.distance) nearest={...p,index};
      });
      this.nodes.forEach((node,index)=>{
        const d=hypot(x-node.x,y-node.y); if(d<nodeDistance){nodeDistance=d;nodeIndex=index;}
      });
      const edgeWidth=Math.max(5.2,8.7*this.scale);
      const radius=Math.max(10.5,(nodeIndex<4 ? 16.5 : 13.0)*this.scale);
      const edgeDensity=Math.exp(-.5*Math.pow(nearest.distance/edgeWidth,2));
      const nodeDensity=Math.exp(-.5*Math.pow(nodeDistance/(radius*.83),2));
      const density=Math.max(edgeDensity,nodeDensity);
      const grain=.70+.30*hash(col+4,row+71);
      const alpha=clamp((.035+.067*hash(col+17,row+3))*fieldFade + .76*Math.pow(density,.8)*grain,.02,.86);
      if(density<.08 && hash(col,row)>.59) continue;
      const character=ALPHABET[Math.floor(hash(col+31,row+11)*4)];
      let binding=null;
      if(density>.13) {
        if(nodeDensity>edgeDensity*.92 && nodeDistance<radius*1.7) {
          const n=this.nodes[nodeIndex]; binding={kind:"node",node:nodeIndex,dx:x-n.x,dy:y-n.y};
        } else {
          const edge=this.edges[nearest.index],a=this.nodes[edge.a],b=this.nodes[edge.b];
          const ux=(b.x-a.x)/edge.length,uy=(b.y-a.y)/edge.length;
          const dx=x-nearest.x,dy=y-nearest.y;
          binding={kind:"edge",edge:nearest.index,t:nearest.t,along:dx*ux+dy*uy,normal:-dx*uy+dy*ux};
        }
      }
      this.particles.push({character,hx:x,hy:y,x,y,vx:0,vy:0,alpha,density,binding});
    }
  }
  target(p) {
    if(!p.binding) return {x:p.hx,y:p.hy};
    const b=p.binding;
    if(b.kind==="node") return {x:this.nodes[b.node].x+b.dx,y:this.nodes[b.node].y+b.dy};
    const edge=this.edges[b.edge],a=this.nodes[edge.a],z=this.nodes[edge.b];
    const length=hypot(z.x-a.x,z.y-a.y) || 1;
    const ux=(z.x-a.x)/length,uy=(z.y-a.y)/length;
    return {x:a.x+(z.x-a.x)*b.t+ux*b.along-uy*b.normal,y:a.y+(z.y-a.y)*b.t+uy*b.along+ux*b.normal};
  }
  point(x,y,active=true) {this.pointer={x,y,active};}
  grab(x,y) {
    this.point(x,y);
    let index=0,distance=Infinity;
    this.nodes.forEach((node,i)=>{const d=hypot(x-node.x,y-node.y);if(d<distance){distance=d;index=i;}});
    if(distance>this.radius*1.5) return false;
    const node=this.nodes[index];
    this.drag={index,startX:x,startY:y,nodeX:node.x,nodeY:node.y};
    return true;
  }
  release(active=false) {this.drag=null;this.pointer.active=active;}
  reset() {
    this.drag=null;this.pointer.active=false;
    this.nodes.forEach(n=>{n.x=n.hx;n.y=n.hy;n.vx=0;n.vy=0;});
    this.particles.forEach(p=>{p.x=p.hx;p.y=p.hy;p.vx=0;p.vy=0;});
  }
  step(seconds) {
    const dt=clamp(seconds,0,1/30),nodeDrag=Math.exp(-7.5*dt),glyphDrag=Math.exp(-12*dt);
    const forces=this.nodes.map(n=>({x:(n.hx-n.x)*14,y:(n.hy-n.y)*14}));
    for(const edge of this.edges) {
      const a=this.nodes[edge.a],b=this.nodes[edge.b],dx=b.x-a.x,dy=b.y-a.y,d=hypot(dx,dy)||1;
      const force=(d-edge.length)*20;
      forces[edge.a].x+=dx/d*force;forces[edge.a].y+=dy/d*force;
      forces[edge.b].x-=dx/d*force;forces[edge.b].y-=dy/d*force;
    }
    for(let i=0;i<this.nodes.length;i++) {
      const n=this.nodes[i];
      if(this.pointer.active && !this.drag) {
        const dx=n.x-this.pointer.x,dy=n.y-this.pointer.y,d=Math.max(1,hypot(dx,dy));
        const weight=Math.pow(clamp(1-d/this.radius,0,1),2);
        forces[i].x+=dx/d*weight*430;forces[i].y+=dy/d*weight*430;
      }
      if(this.drag?.index===i) {
        let dx=this.pointer.x-this.drag.startX,dy=this.pointer.y-this.drag.startY;
        const d=hypot(dx,dy);if(d>this.limit){dx*=this.limit/d;dy*=this.limit/d;}
        forces[i].x+=(this.drag.nodeX+dx-n.x)*125;forces[i].y+=(this.drag.nodeY+dy-n.y)*125;
      }
      const mass=i===0 ? 1.65 : 1;
      n.vx=(n.vx+forces[i].x/mass*dt)*nodeDrag;n.vy=(n.vy+forces[i].y/mass*dt)*nodeDrag;
      n.x+=n.vx*dt;n.y+=n.vy*dt;
      const dx=n.x-n.hx,dy=n.y-n.hy,d=hypot(dx,dy);
      if(d>this.limit){n.x=n.hx+dx/d*this.limit;n.y=n.hy+dy/d*this.limit;n.vx*=.5;n.vy*=.5;}
      n.x=clamp(n.x,24,this.width-24);n.y=clamp(n.y,24,this.height-24);
    }
    let energy=0;
    for(const p of this.particles) {
      const target=this.target(p);
      let fx=(target.x-p.x)*60,fy=(target.y-p.y)*60;
      if(this.pointer.active) {
        let dx=p.x-this.pointer.x,dy=p.y-this.pointer.y;
        let d=hypot(dx,dy);
        if(d<.2){dx=.2;dy=.1;d=hypot(dx,dy);}
        const w=Math.pow(clamp(1-d/(this.radius*.74),0,1),2);
        fx+=dx/d*w*1180;fy+=dy/d*w*1180;
      }
      p.vx=(p.vx+fx*dt)*glyphDrag;p.vy=(p.vy+fy*dt)*glyphDrag;
      p.x+=p.vx*dt;p.y+=p.vy*dt;
      const dx=p.x-target.x,dy=p.y-target.y,d=hypot(dx,dy),max=23;
      if(d>max){p.x=target.x+dx/d*max;p.y=target.y+dy/d*max;p.vx*=.4;p.vy*=.4;}
      energy=Math.max(energy,hypot(p.vx,p.vy));
    }
    for(const n of this.nodes) energy=Math.max(energy,hypot(n.vx,n.vy));
    return energy;
  }
}

class KastField {
  constructor(canvas,{theme="pale",motion=true}={}) {
    if(!(canvas instanceof HTMLCanvasElement)) throw new TypeError("KastField needs a canvas element");
    if(!Object.hasOwn(PALETTES,theme)) throw new RangeError("Unknown Kast field theme");
    this.canvas=canvas;this.context=canvas.getContext("2d");this.theme=theme;this.motion=motion;
    this.frame=0;this.lastTime=0;this.quiet=0;this.visible=true;this.destroyed=false;this.pointerId=null;
    this.abort=new AbortController();this.reduced=matchMedia("(prefers-reduced-motion: reduce)");
    this.onPreference=()=>{if(this.reduced.matches)this.reset();else this.wake();};
    this.reduced.addEventListener("change",this.onPreference);
    const opts={signal:this.abort.signal};
    // The field sits behind text. Observe the parent without capturing input.
    const interaction = document;
    interaction.addEventListener("pointermove", e => {
      if (e.pointerType !== "mouse" || !this.model) return;
      const rect = canvas.getBoundingClientRect();
      if (e.clientX < rect.left || e.clientX > rect.right || e.clientY < rect.top || e.clientY > rect.bottom) {
        if (this.model.pointer.active) { this.model.release(); this.wake(); }
        return;
      }
      this.handlePointer(e); this.wake();
    }, opts);
    interaction.addEventListener("pointerout", e => { if (!e.relatedTarget) { this.model?.release(); this.wake(); } }, opts);
    document.addEventListener("visibilitychange",()=>{if(document.hidden)this.stop();else this.wake();},opts);
    this.resizeObserver=new ResizeObserver(()=>this.resize());this.resizeObserver.observe(canvas);
    this.intersectionObserver=new IntersectionObserver(entries=>{
      this.visible=entries[0].isIntersecting;
      if(this.visible)this.wake();else{this.model?.release();this.stop();}
    });
    this.intersectionObserver.observe(canvas);
    this.resize();
  }
  get canMove(){return this.motion && !this.reduced.matches;}
  resize() {
    const rect=this.canvas.getBoundingClientRect();
    if(rect.width<1 || rect.height<1)return;
    const width=Math.round(rect.width),height=Math.round(rect.height);
    if(this.model?.width===width && this.model?.height===height)return;
    this.stop();
    const ratio=Math.min(devicePixelRatio || 1,2);
    this.canvas.width=Math.round(width*ratio);this.canvas.height=Math.round(height*ratio);
    this.context.setTransform(ratio,0,0,ratio,0,0);
    this.model=new FieldModel(width,height);this.draw();
  }
  handlePointer(event) {
    if(this.pointerId!==null && event.pointerId!==this.pointerId)return;
    const rect=this.canvas.getBoundingClientRect();
    this.model.point((event.clientX-rect.left)*this.model.width/rect.width,(event.clientY-rect.top)*this.model.height/rect.height);
  }
  stop(){cancelAnimationFrame(this.frame);this.frame=0;this.lastTime=0;}
  wake() {
    if(this.destroyed || !this.model || !this.visible || document.hidden)return;
    if(!this.canMove){this.draw();return;}
    this.quiet=0;
    if(!this.frame)this.frame=requestAnimationFrame(time=>this.tick(time));
  }
  tick(time) {
    this.frame=0;
    if(this.destroyed || !this.canMove || !this.visible || document.hidden)return;
    const elapsed=this.lastTime ? Math.min((time-this.lastTime)/1000,1/30) : 1/60;
    this.lastTime=time;
    const energy=this.model.step(elapsed);this.draw();
    this.quiet=energy<.13 ? this.quiet+1 : 0;
    if(this.quiet>22 && !this.model.drag) {
      if(!this.model.pointer.active){this.model.reset();this.draw();}
      this.lastTime=0;return;
    }
    this.frame=requestAnimationFrame(next=>this.tick(next));
  }
  setTheme(theme) {
    if(!Object.hasOwn(PALETTES,theme))throw new RangeError("Unknown Kast field theme");
    this.theme=theme;this.draw();
  }
  setMotion(enabled) {
    this.motion=Boolean(enabled);
    if(!this.motion){this.stop();this.model.release();this.draw();}else this.wake();
  }
  reset(){this.stop();this.model?.reset();this.draw();}
  draw() {
    if(!this.model)return;
    const ctx=this.context,m=this.model,palette=PALETTES[this.theme];
    ctx.globalAlpha=1;ctx.clearRect(0,0,m.width,m.height);
    if(m.pointer.active) {
      ctx.strokeStyle=palette.edge;ctx.lineWidth=.65;
      for(const e of m.edges) {
        const a=m.nodes[e.a],b=m.nodes[e.b];
        const d=onSegment(m.pointer.x,m.pointer.y,a,b).distance;
        const influence=clamp(1-d/(m.radius*1.35),0,1);
        if(!influence)continue;
        ctx.globalAlpha=influence*.23;ctx.beginPath();ctx.moveTo(a.x,a.y);ctx.lineTo(b.x,b.y);ctx.stroke();
      }
    }
    ctx.font=`500 ${m.fontSize}px "SFMono-Regular",Consolas,"Liberation Mono",monospace`;
    ctx.textAlign="center";ctx.textBaseline="middle";ctx.fillStyle=palette.glyph;
    for(const p of m.particles) {
      const influence=m.pointer.active ? Math.pow(clamp(1-hypot(p.x-m.pointer.x,p.y-m.pointer.y)/m.radius,0,1),2) : 0;
      ctx.globalAlpha=clamp(p.alpha+influence*(.24+.25*p.density),0,.96);
      ctx.fillText(p.character,p.x,p.y);
    }
    ctx.globalAlpha=1;
  }
  toSVG({background=true}={}) {
    const m=this.model,palette=PALETTES[this.theme];
    const f=n=>n.toFixed(2).replace(/\.00$/,"");
    const parts=[`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" width="${m.width}" height="${m.height}" role="img" aria-label="Kast folded-space logo formed from K A S T characters">`,
      '<title>Kast — reactive field, static snapshot</title>'];
    if(background)parts.push(`<rect width="100%" height="100%" fill="${palette.background}"/>`);
    parts.push(`<g fill="${palette.glyph}" font-family="monospace" font-size="${m.fontSize}" font-weight="500" text-anchor="middle" dominant-baseline="central">`);
    for(const p of m.particles)parts.push(`<text x="${f(p.x)}" y="${f(p.y)}" opacity="${p.alpha.toFixed(3)}">${p.character}</text>`);
    parts.push('</g></svg>');return parts.join('\n');
  }
  destroy() {
    if(this.destroyed)return;
    this.destroyed=true;this.stop();this.abort.abort();
    this.resizeObserver.disconnect();this.intersectionObserver.disconnect();
    this.reduced.removeEventListener("change",this.onPreference);
    if(this.pointerId!==null && this.canvas.hasPointerCapture(this.pointerId))this.canvas.releasePointerCapture(this.pointerId);
  }
}

let field = null;
let fieldCanvas = null;
function mountField() {
  const container = document.querySelector('.kast-field');
  let canvas = container?.querySelector('canvas');
  if (canvas === fieldCanvas && canvas) return;
  if (container && !canvas) { canvas = document.createElement('canvas'); container.append(canvas); }
  if (field) field.destroy();
  field = null; fieldCanvas = canvas;
  if (!canvas) return;
  canvas.parentElement.classList.add('kast-field-ready');
  field = new KastField(canvas, {theme: document.documentElement.classList.contains('dark') ? 'graphite' : 'pale'});
}
new MutationObserver(() => {
  mountField();
  if (field) field.setTheme(document.documentElement.classList.contains('dark') ? 'graphite' : 'pale');
}).observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['class']});
mountField();

})();
