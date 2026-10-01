const SOLAR:f64=4.0*std::f64::consts::PI*std::f64::consts::PI; const DPY:f64=365.24;
#[derive(Clone,Copy)] struct P{x:f64,y:f64,z:f64,vx:f64,vy:f64,vz:f64,m:f64}
fn advance(b:&mut [P;5],dt:f64){
 for i in 0..5{ for j in i+1..5{
  let dx=b[i].x-b[j].x;let dy=b[i].y-b[j].y;let dz=b[i].z-b[j].z;
  let d2=dx*dx+dy*dy+dz*dz;let mag=dt/(d2*d2.sqrt());
  let mj=b[j].m;let mi=b[i].m;
  b[i].vx-=dx*mj*mag;b[i].vy-=dy*mj*mag;b[i].vz-=dz*mj*mag;
  b[j].vx+=dx*mi*mag;b[j].vy+=dy*mi*mag;b[j].vz+=dz*mi*mag;}}
 for p in b.iter_mut(){p.x+=dt*p.vx;p.y+=dt*p.vy;p.z+=dt*p.vz;}}
fn energy(b:&[P;5])->f64{let mut e=0.0;for i in 0..5{let p=b[i];e+=0.5*p.m*(p.vx*p.vx+p.vy*p.vy+p.vz*p.vz);
 for j in i+1..5{let q=b[j];let dx=p.x-q.x;let dy=p.y-q.y;let dz=p.z-q.z;e-=p.m*q.m/(dx*dx+dy*dy+dz*dz).sqrt();}}e}
fn main(){let n:u64=std::env::args().nth(1).unwrap().parse().unwrap();
 let mut b=[P{x:0.0,y:0.0,z:0.0,vx:0.0,vy:0.0,vz:0.0,m:SOLAR},
 P{x:4.84143144246472090e+00,y:-1.16032004402742839e+00,z:-1.03622044471123109e-01,vx:1.66007664274403694e-03*DPY,vy:7.69901118419740425e-03*DPY,vz:-6.90460016972063023e-05*DPY,m:9.54791938424326609e-04*SOLAR},
 P{x:8.34336671824457987e+00,y:4.12479856412430479e+00,z:-4.03523417114321381e-01,vx:-2.76742510726862411e-03*DPY,vy:4.99852801234917238e-03*DPY,vz:2.30417297573763929e-05*DPY,m:2.85885980666130812e-04*SOLAR},
 P{x:1.28943695621391310e+01,y:-1.51111514016986312e+01,z:-2.23307578892655734e-01,vx:2.96460137564761618e-03*DPY,vy:2.37847173959480950e-03*DPY,vz:-2.96589568540237556e-05*DPY,m:4.36624404335156298e-05*SOLAR},
 P{x:1.53796971148509165e+01,y:-2.59193146099879641e+01,z:1.79258772950371181e-01,vx:2.68067772490389322e-03*DPY,vy:1.62824170038242295e-03*DPY,vz:-9.51592254519715870e-05*DPY,m:5.15138902046611451e-05*SOLAR}];
 let(mut px,mut py,mut pz)=(0.0,0.0,0.0);for p in b.iter(){px+=p.vx*p.m;py+=p.vy*p.m;pz+=p.vz*p.m;}
 b[0].vx=-px/SOLAR;b[0].vy=-py/SOLAR;b[0].vz=-pz/SOLAR;
 println!("{:.9}",energy(&b));for _ in 0..n{advance(&mut b,0.01);}println!("{:.9}",energy(&b));}
