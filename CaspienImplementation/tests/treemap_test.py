m={}; x=777; log=[]
removed=found=0
for i in range(6000):
    x=(x*1103515245+12345)%2147483648
    op=(x>>16)%4; k=(x>>8)%1500
    if op<2: m[k]=i
    elif op==2:
        if k in m: removed+=1; del m[k]
    else:
        if k in m: found+=1
ks=sorted(m)
print(len(m),removed,found,sum(ks),sum(m.values()),ks[0],ks[-1], sum((i+1)*k for i,k in enumerate(ks))%1000003)
