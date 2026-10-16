const SOLAR=4*Math.PI*Math.PI,DPY=365.24;
const b=[[0,0,0,0,0,0,SOLAR],
[4.84143144246472090e+00,-1.16032004402742839e+00,-1.03622044471123109e-01,1.66007664274403694e-03*DPY,7.69901118419740425e-03*DPY,-6.90460016972063023e-05*DPY,9.54791938424326609e-04*SOLAR],
[8.34336671824457987e+00,4.12479856412430479e+00,-4.03523417114321381e-01,-2.76742510726862411e-03*DPY,4.99852801234917238e-03*DPY,2.30417297573763929e-05*DPY,2.85885980666130812e-04*SOLAR],
[1.28943695621391310e+01,-1.51111514016986312e+01,-2.23307578892655734e-01,2.96460137564761618e-03*DPY,2.37847173959480950e-03*DPY,-2.96589568540237556e-05*DPY,4.36624404335156298e-05*SOLAR],
[1.53796971148509165e+01,-2.59193146099879641e+01,1.79258772950371181e-01,2.68067772490389322e-03*DPY,1.62824170038242295e-03*DPY,-9.51592254519715870e-05*DPY,5.15138902046611451e-05*SOLAR]];
function advance(dt){
 for(let i=0;i<5;i++){const p=b[i];for(let j=i+1;j<5;j++){const q=b[j];
  const dx=p[0]-q[0],dy=p[1]-q[1],dz=p[2]-q[2],d2=dx*dx+dy*dy+dz*dz,mag=dt/(d2*Math.sqrt(d2));
  p[3]-=dx*q[6]*mag;p[4]-=dy*q[6]*mag;p[5]-=dz*q[6]*mag;q[3]+=dx*p[6]*mag;q[4]+=dy*p[6]*mag;q[5]+=dz*p[6]*mag;}}
 for(const p of b){p[0]+=dt*p[3];p[1]+=dt*p[4];p[2]+=dt*p[5];}}
function energy(){let e=0;for(let i=0;i<5;i++){const p=b[i];e+=0.5*p[6]*(p[3]*p[3]+p[4]*p[4]+p[5]*p[5]);
 for(let j=i+1;j<5;j++){const q=b[j],dx=p[0]-q[0],dy=p[1]-q[1],dz=p[2]-q[2];e-=p[6]*q[6]/Math.sqrt(dx*dx+dy*dy+dz*dz);}}return e;}
const n=parseInt(process.argv[2]);let px=0,py=0,pz=0;
for(const p of b){px+=p[3]*p[6];py+=p[4]*p[6];pz+=p[5]*p[6];}
b[0][3]=-px/SOLAR;b[0][4]=-py/SOLAR;b[0][5]=-pz/SOLAR;
console.log(energy().toFixed(9));for(let i=0;i<n;i++)advance(0.01);console.log(energy().toFixed(9));
