# insertion-ordered dict model (python dict keeps order; re-set keeps position; delete+re-add goes to the end)
m={}; x=4242; removed=found=0
for i in range(6000):
    x=(x*1103515245+12345)%2147483648
    op=(x>>16)%4; k=(x>>8)%1500
    if op<2: m[k]=i
    elif op==2:
        if k in m: removed+=1; del m[k]
    else:
        if k in m: found+=1
ks=list(m)
print(len(m),removed,found,sum(ks),sum(m.values()),ks[0],ks[-1],sum((i+1)*k for i,k in enumerate(ks))%1000003,sum((i+1)*m[k] for i,k in enumerate(ks))%1000003)
