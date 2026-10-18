# insertion-ordered set model (python dict keeps order; re-add keeps position; delete+re-add goes to the end)
m={}; x=777; added=removed=found=0
for i in range(8000):
    x=(x*1103515245+12345)%2147483648
    op=(x>>16)%4; k=(x>>8)%1200
    if op<2:
        if k not in m: added+=1; m[k]=1
    elif op==2:
        if k in m: removed+=1; del m[k]
    else:
        if k in m: found+=1
ks=list(m)
print(len(m),added,removed,found,sum(ks),ks[0],ks[-1],sum((i+1)*k for i,k in enumerate(ks))%1000003)
