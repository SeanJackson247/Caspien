s=set(); x=12345; added=removed=found=0
for i in range(20000):
    x=(x*1103515245+12345)%2147483648
    op=(x>>16)%4; k=(x>>8)%5000
    if op<2:
        if k not in s: added+=1
        s.add(k)
    elif op==2:
        if k in s: removed+=1; s.discard(k)
    else:
        if k in s: found+=1
print(len(s),added,removed,found)
