package main
import ("fmt";"math";"os";"strconv")
const solar = 4*math.Pi*math.Pi
const dpy = 365.24
type P struct{ x,y,z,vx,vy,vz,m float64 }
var b = [5]P{
 {0,0,0,0,0,0,solar},
 {4.84143144246472090e+00,-1.16032004402742839e+00,-1.03622044471123109e-01,1.66007664274403694e-03*dpy,7.69901118419740425e-03*dpy,-6.90460016972063023e-05*dpy,9.54791938424326609e-04*solar},
 {8.34336671824457987e+00,4.12479856412430479e+00,-4.03523417114321381e-01,-2.76742510726862411e-03*dpy,4.99852801234917238e-03*dpy,2.30417297573763929e-05*dpy,2.85885980666130812e-04*solar},
 {1.28943695621391310e+01,-1.51111514016986312e+01,-2.23307578892655734e-01,2.96460137564761618e-03*dpy,2.37847173959480950e-03*dpy,-2.96589568540237556e-05*dpy,4.36624404335156298e-05*solar},
 {1.53796971148509165e+01,-2.59193146099879641e+01,1.79258772950371181e-01,2.68067772490389322e-03*dpy,1.62824170038242295e-03*dpy,-9.51592254519715870e-05*dpy,5.15138902046611451e-05*solar}}
func advance(dt float64){
 for i:=0;i<5;i++{ for j:=i+1;j<5;j++{
  dx:=b[i].x-b[j].x; dy:=b[i].y-b[j].y; dz:=b[i].z-b[j].z
  d2:=dx*dx+dy*dy+dz*dz; mag:=dt/(d2*math.Sqrt(d2))
  b[i].vx-=dx*b[j].m*mag; b[i].vy-=dy*b[j].m*mag; b[i].vz-=dz*b[j].m*mag
  b[j].vx+=dx*b[i].m*mag; b[j].vy+=dy*b[i].m*mag; b[j].vz+=dz*b[i].m*mag}}
 for i:=range b{ b[i].x+=dt*b[i].vx; b[i].y+=dt*b[i].vy; b[i].z+=dt*b[i].vz}
}
func energy() float64{ e:=0.0
 for i:=0;i<5;i++{ e+=0.5*b[i].m*(b[i].vx*b[i].vx+b[i].vy*b[i].vy+b[i].vz*b[i].vz)
  for j:=i+1;j<5;j++{ dx:=b[i].x-b[j].x; dy:=b[i].y-b[j].y; dz:=b[i].z-b[j].z; e-=b[i].m*b[j].m/math.Sqrt(dx*dx+dy*dy+dz*dz)}}
 return e}
func main(){ n,_:=strconv.Atoi(os.Args[1]); var px,py,pz float64
 for i:=range b{px+=b[i].vx*b[i].m;py+=b[i].vy*b[i].m;pz+=b[i].vz*b[i].m}
 b[0].vx=-px/solar;b[0].vy=-py/solar;b[0].vz=-pz/solar
 fmt.Printf("%.9f\n",energy()); for i:=0;i<n;i++{advance(0.01)}; fmt.Printf("%.9f\n",energy())}
