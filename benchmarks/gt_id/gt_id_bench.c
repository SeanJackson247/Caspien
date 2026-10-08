// Prototype/benchmark of the ghost-table designs for "nullable ref = 64-bit id, ref some = address" (8 Oct 2026).
// All tables are open addressing, linear probing, power-of-two capacity, load <= 1/2, backward-shift deletion (exactly like stdlib/ghost_table.caspien).
//   A   current: one set of addresses (8 B/entry).            alive(addr) = 1 probe
//   B   two indexes: addr -> {addr,id} and id -> {id,addr}    (16 B/entry each). ref(addr) = 1 probe, resolve(id) = 1 probe, register/free = 2 updates
//   C   id stored in the object header (+8 B/object) + one id -> addr index.   ref(addr) = header read, resolve(id) = 1 probe
//   B/C id hash: "mix" (multiplicative) or "seq" (id & mask; ids are a counter, so live ids are nearly consecutive)
// Single thread, no lock (the real table takes a spin lock for every operation; that cost is identical in all variants).
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <time.h>

typedef uint64_t u64;
static double now(void){ struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec+t.tv_nsec*1e-9; }

static u64 rng_s = 88172645463325252ULL;
static inline u64 rnd(void){ rng_s ^= rng_s<<13; rng_s ^= rng_s>>7; rng_s ^= rng_s<<17; return rng_s; }

// ---------- generic table: key (u64, 0 = empty) + optional value ----------
typedef struct { u64 k, v; } E16;
typedef struct { u64 *k; u64 cap, len; } T8;       // key-only
typedef struct { E16 *e; u64 cap, len; int seq; } T16;   // key + value

static inline u64 hash_addr(u64 a, u64 mask){ u64 h = a>>4; h ^= h>>11; return h & mask; }
static inline u64 hash_idmix(u64 id, u64 mask){ id *= 0x9E3779B97F4A7C15ULL; return (id>>32) & mask; }
static inline u64 hash_idseq(u64 id, u64 mask){ return id & mask; }
// "grp": 4 consecutive ids share one cache line (4 x 16 B), groups are scattered by a multiplicative hash
static inline u64 hash_idgrp(u64 id, u64 mask){ u64 g = (id>>2) * 0x9E3779B97F4A7C15ULL; return ((((g>>32)<<2) | (id&3)) & mask); }

// ---- T8 (variant A) ----
static void t8_init(T8*t,u64 cap){ t->cap=cap; t->len=0; t->k=calloc(cap,8); }
static inline u64 t8_find(T8*t,u64 key){ u64 m=t->cap-1,i=hash_addr(key,m); for(;;){ u64 v=t->k[i]; if(!v) return ~0ULL; if(v==key) return i; i=(i+1)&m; } }
static void t8_insert_raw(u64*k,u64 cap,u64 key){ u64 m=cap-1,i=hash_addr(key,m); while(k[i]) i=(i+1)&m; k[i]=key; }
static void t8_grow(T8*t){ u64 nc=t->cap*2; u64*nk=calloc(nc,8); for(u64 i=0;i<t->cap;i++) if(t->k[i]) t8_insert_raw(nk,nc,t->k[i]); free(t->k); t->k=nk; t->cap=nc; }
static void t8_insert(T8*t,u64 key){ if((t->len+1)*2>t->cap) t8_grow(t); t8_insert_raw(t->k,t->cap,key); t->len++; }
static void t8_remove(T8*t,u64 key){ u64 i=t8_find(t,key); if(i==~0ULL) return; u64 m=t->cap-1,hole=i,j=i; for(;;){ j=(j+1)&m; u64 v=t->k[j]; if(!v) break; u64 home=hash_addr(v,m); if(((j-home)&m) >= ((j-hole)&m)){ t->k[hole]=v; hole=j; } } t->k[hole]=0; t->len--; }

// tagged variant: bit 0 of an entry = "this object has an id" (addresses are 16-byte aligned)
static inline u64 t8t_find(T8*t,u64 key){ u64 m=t->cap-1,i=hash_addr(key,m); for(;;){ u64 v=t->k[i]; if(!v) return ~0ULL; if((v&~1ULL)==key) return i; i=(i+1)&m; } }
static void t8t_remove_at(T8*t,u64 i){ u64 m=t->cap-1,hole=i,j=i; for(;;){ j=(j+1)&m; u64 v=t->k[j]; if(!v) break; u64 home=hash_addr(v&~1ULL,m); if(((j-home)&m) >= ((j-hole)&m)){ t->k[hole]=v; hole=j; } } t->k[hole]=0; t->len--; }
// ---- T16 (key,value; hash by address or by id) ----
// mode: 0 = by address, 1 = by id mix, 2 = by id seq
static inline u64 h16(int mode,u64 key,u64 mask){ return mode==0?hash_addr(key,mask):mode==1?hash_idmix(key,mask):mode==2?hash_idseq(key,mask):hash_idgrp(key,mask); }
static void t16_init(T16*t,u64 cap,int mode){ t->cap=cap; t->len=0; t->seq=mode; t->e=calloc(cap,16); }
static inline u64 t16_find(T16*t,u64 key){ u64 m=t->cap-1,i=h16(t->seq,key,m); for(;;){ u64 v=t->e[i].k; if(!v) return ~0ULL; if(v==key) return i; i=(i+1)&m; } }
static void t16_insert_raw(E16*e,u64 cap,int mode,u64 key,u64 val){ u64 m=cap-1,i=h16(mode,key,m); while(e[i].k) i=(i+1)&m; e[i].k=key; e[i].v=val; }
static void t16_grow(T16*t){ u64 nc=t->cap*2; E16*ne=calloc(nc,16); for(u64 i=0;i<t->cap;i++) if(t->e[i].k) t16_insert_raw(ne,nc,t->seq,t->e[i].k,t->e[i].v); free(t->e); t->e=ne; t->cap=nc; }
static void t16_insert(T16*t,u64 key,u64 val){ if((t->len+1)*2>t->cap) t16_grow(t); t16_insert_raw(t->e,t->cap,t->seq,key,val); t->len++; }
static void t16_remove_at(T16*t,u64 i){ u64 m=t->cap-1,hole=i,j=i; for(;;){ j=(j+1)&m; E16 v=t->e[j]; if(!v.k) break; u64 home=h16(t->seq,v.k,m); if(((j-home)&m) >= ((j-hole)&m)){ t->e[hole]=v; hole=j; } } t->e[hole].k=0; t->len--; }

// ---------- the three designs ----------
static u64 next_id = 1;
typedef struct { T8 a; } DA;
typedef struct { T16 byaddr, byid; } DB;
typedef struct { T16 byid; } DC;

static void* NODE(size_t hdr){ return malloc(32+hdr); }   // 32-byte payload (+8 header in C)

#define NOW now()
static double ns(double t0,double t1,u64 n){ return (t1-t0)*1e9/(double)n; }

static void run(u64 N, int idmode){
    const char*mn = idmode==1?"mix":idmode==2?"seq":"grp";
    u64 *ptr = malloc(N*8), *ptrC = malloc(N*8), *ids = malloc(N*8), *idsC = malloc(N*8);
    u64 *order = malloc(N*8);
    for(u64 i=0;i<N;i++) order[i]=i;
    for(u64 i=N-1;i>0;i--){ u64 j=rnd()%(i+1); u64 t=order[i]; order[i]=order[j]; order[j]=t; }
    // allocate the objects once for A/B (plain) and C (with header)
    for(u64 i=0;i<N;i++){ ptr[i]=(u64)NODE(0); ptrC[i]=(u64)NODE(8); }
    DA A; DB B; DC C;
    t8_init(&A.a,16); t16_init(&B.byaddr,16,0); t16_init(&B.byid,16,idmode); t16_init(&C.byid,16,idmode);
    double t0,t1; volatile u64 sink=0;
    printf("\n=== N = %llu live objects, id hash = %s ===\n",(unsigned long long)N,mn);
    printf("%-34s %9s %9s %9s\n","ns per operation","A addr","B 2-index","C header");
    // register
    t0=NOW; for(u64 i=0;i<N;i++) t8_insert(&A.a,ptr[i]); t1=NOW; double rA=ns(t0,t1,N);
    t0=NOW; for(u64 i=0;i<N;i++){ u64 id=next_id++; ids[i]=id; t16_insert(&B.byaddr,ptr[i],id); t16_insert(&B.byid,id,ptr[i]); } t1=NOW; double rB=ns(t0,t1,N);
    t0=NOW; for(u64 i=0;i<N;i++){ u64 id=next_id++; idsC[i]=id; *(u64*)ptrC[i]=id; t16_insert(&C.byid,id,ptrC[i]+8); } t1=NOW; double rC=ns(t0,t1,N);
    printf("%-34s %9.1f %9.1f %9.1f\n","register (incl. table growth)",rA,rB,rC);
    // addr -> id  (creating a ref), random order
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; u64 j=t16_find(&B.byaddr,ptr[i]); sink+=B.byaddr.e[j].v; } t1=NOW; double cB=ns(t0,t1,N);
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; sink+=*(u64*)ptrC[i]; } t1=NOW; double cC=ns(t0,t1,N);
    printf("%-34s %9s %9.1f %9.1f\n","ref x: address -> id",  "free",cB,cC);
    // resolve live
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; sink+= t8_find(&A.a,ptr[i])!=~0ULL; } t1=NOW; double lA=ns(t0,t1,N);
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; u64 j=t16_find(&B.byid,ids[i]); sink+=B.byid.e[j].v; } t1=NOW; double lB=ns(t0,t1,N);
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; u64 j=t16_find(&C.byid,idsC[i]); sink+=C.byid.e[j].v; } t1=NOW; double lC=ns(t0,t1,N);
    printf("%-34s %9.1f %9.1f %9.1f\n","match Some: resolve live (random)",lA,lB,lC);
    // resolve in id order (a structure built in sequence and walked in sequence)
    t0=NOW; for(u64 i=0;i<N;i++){ sink+= t8_find(&A.a,ptr[i])!=~0ULL; } t1=NOW; double sA=ns(t0,t1,N);
    t0=NOW; for(u64 i=0;i<N;i++){ u64 j=t16_find(&B.byid,ids[i]); sink+=B.byid.e[j].v; } t1=NOW; double sB=ns(t0,t1,N);
    t0=NOW; for(u64 i=0;i<N;i++){ u64 j=t16_find(&C.byid,idsC[i]); sink+=C.byid.e[j].v; } t1=NOW; double sC=ns(t0,t1,N);
    printf("%-34s %9.1f %9.1f %9.1f\n","match Some: resolve live (in order)",sA,sB,sC);
    // free the first half (random order), then resolve those dead refs
    u64 H=N/2;
    t0=NOW; for(u64 k=0;k<H;k++){ u64 i=order[k]; t8_remove(&A.a,ptr[i]); } t1=NOW; double fA=ns(t0,t1,H);
    t0=NOW; for(u64 k=0;k<H;k++){ u64 i=order[k]; u64 j=t16_find(&B.byaddr,ptr[i]); u64 id=B.byaddr.e[j].v; t16_remove_at(&B.byaddr,j); u64 j2=t16_find(&B.byid,id); t16_remove_at(&B.byid,j2); } t1=NOW; double fB=ns(t0,t1,H);
    t0=NOW; for(u64 k=0;k<H;k++){ u64 i=order[k]; u64 id=*(u64*)ptrC[i]; u64 j=t16_find(&C.byid,id); t16_remove_at(&C.byid,j); } t1=NOW; double fC=ns(t0,t1,H);
    printf("%-34s %9.1f %9.1f %9.1f\n","free (unregister, random order)",fA,fB,fC);
    t0=NOW; for(u64 k=0;k<H;k++){ u64 i=order[k]; sink+= t8_find(&A.a,ptr[i])!=~0ULL; } t1=NOW; double dA=ns(t0,t1,H);
    t0=NOW; for(u64 k=0;k<H;k++){ u64 i=order[k]; sink+= t16_find(&B.byid,ids[i])!=~0ULL; } t1=NOW; double dB=ns(t0,t1,H);
    t0=NOW; for(u64 k=0;k<H;k++){ u64 i=order[k]; sink+= t16_find(&C.byid,idsC[i])!=~0ULL; } t1=NOW; double dC=ns(t0,t1,H);
    printf("%-34s %9.1f %9.1f %9.1f\n","match Some: resolve DEAD",dA,dB,dC);
    // steady-state churn: free one random live, allocate one, N times (live set ~N/2 now)
    u64 live = N-H; u64 *lp=malloc(live*8),*li=malloc(live*8),*lpC=malloc(live*8),*liC=malloc(live*8);
    u64 q=0; for(u64 k=H;k<N;k++){ u64 i=order[k]; lp[q]=ptr[i]; li[q]=ids[i]; lpC[q]=ptrC[i]; liC[q]=idsC[i]; q++; }
    u64 R = N; 
    t0=NOW; for(u64 r=0;r<R;r++){ u64 s=rnd()%live; t8_remove(&A.a,lp[s]); t8_insert(&A.a,lp[s]); } t1=NOW; double hA=ns(t0,t1,R);   // address reused, as malloc does
    t0=NOW; for(u64 r=0;r<R;r++){ u64 s=rnd()%live; u64 j=t16_find(&B.byaddr,lp[s]); u64 id=B.byaddr.e[j].v; t16_remove_at(&B.byaddr,j); u64 j2=t16_find(&B.byid,id); t16_remove_at(&B.byid,j2);
        u64 nid=next_id++; li[s]=nid; t16_insert(&B.byaddr,lp[s],nid); t16_insert(&B.byid,nid,lp[s]); } t1=NOW; double hB=ns(t0,t1,R);
    t0=NOW; for(u64 r=0;r<R;r++){ u64 s=rnd()%live; u64 id=*(u64*)lpC[s]; u64 j=t16_find(&C.byid,id); t16_remove_at(&C.byid,j);
        u64 nid=next_id++; liC[s]=nid; *(u64*)lpC[s]=nid; t16_insert(&C.byid,nid,lpC[s]+8); } t1=NOW; double hC=ns(t0,t1,R);
    printf("%-34s %9.1f %9.1f %9.1f\n","free+allocate pair (churn)",hA,hB,hC);
    // pointer chase through a randomly linked list, resolving every hop
    // A: next stored as address, alive check per hop; B/C: next stored as id, resolve per hop
    u64 L=live; u64 *perm=malloc(L*8); for(u64 i=0;i<L;i++) perm[i]=i; for(u64 i=L-1;i>0;i--){ u64 j=rnd()%(i+1); u64 t=perm[i]; perm[i]=perm[j]; perm[j]=t; }
    // node payload word 0 (after header in C) = index of next node in the permutation cycle
    for(u64 k=0;k<L;k++){ u64 cur=perm[k], nx=perm[(k+1)%L]; *(u64*)(lp[cur]) = lp[nx]; *(u64*)(lpC[cur]+8) = liC[nx]; }
    u64 hops = 2*L; u64 cur=lp[perm[0]]; 
    t0=NOW; for(u64 h=0;h<hops;h++){ u64 nx=*(u64*)cur; sink+= t8_find(&A.a,nx)!=~0ULL; cur=nx; } t1=NOW; double pA=ns(t0,t1,hops);
    // B: the stored value is an id for every hop; need the id of each node: store ids in payload too
    for(u64 k=0;k<L;k++){ u64 cu=perm[k], nx=perm[(k+1)%L]; *(u64*)(lp[cu]) = li[nx]; }
    u64 curid=li[perm[0]]; 
    t0=NOW; for(u64 h=0;h<hops;h++){ u64 j=t16_find(&B.byid,curid); u64 addr=B.byid.e[j].v; curid=*(u64*)addr; } t1=NOW; double pB=ns(t0,t1,hops);
    curid=liC[perm[0]];
    t0=NOW; for(u64 h=0;h<hops;h++){ u64 j=t16_find(&C.byid,curid); u64 addr=C.byid.e[j].v; curid=*(u64*)addr; } t1=NOW; double pC=ns(t0,t1,hops);
    printf("%-34s %9.1f %9.1f %9.1f\n","linked-list hop (resolve + load)",pA,pB,pC);
    printf("%-34s %9.1f %9.1f %9.1f\n","table bytes per live object",(double)A.a.cap*8/(double)A.a.len,((double)B.byaddr.cap+B.byid.cap)*16/(double)B.byid.len,((double)C.byid.cap*16+8*(double)C.byid.len)/(double)C.byid.len);
    (void)sink;
}

// ---------- D: lazy ids ----------
// The address table has 16 B entries {addr, id}; id = 0 until the first `ref x` of that object. Only objects that are ever referenced through a
// nullable ref get an entry in the id -> address index. Free: one address probe, plus one id removal if the object ever got an id.
static void runLazy(u64 N, double f){
    u64 *ptr=malloc(N*8), *order=malloc(N*8);
    for(u64 i=0;i<N;i++) order[i]=i;
    for(u64 i=N-1;i>0;i--){ u64 j=rnd()%(i+1); u64 t=order[i]; order[i]=order[j]; order[j]=t; }
    for(u64 i=0;i<N;i++) ptr[i]=(u64)NODE(0);
    u64 *refd=malloc(N*8); u64 nref=0;                  // indexes (in `order`) that will get an id
    for(u64 k=0;k<N;k++) if((double)(rnd()%1000000)/1e6 < f) refd[nref++]=order[k];
    T8 A; T16 L, I; t8_init(&A,16); t16_init(&L,16,0); t16_init(&I,16,1);
    u64 *ids=calloc(N,8); volatile u64 sink=0; double t0,t1;
    printf("\n=== N = %llu, %.0f%% of objects ever get a nullable ref (lazy ids) ===\n",(unsigned long long)N,f*100);
    printf("%-34s %9s %9s %9s\n","ns per operation","A addr","D lazy16","E tag8");
    T8 Et; t8_init(&Et,16); T16 Ea, Ei; t16_init(&Ea,16,0); t16_init(&Ei,16,1); u64 *idsE=calloc(N,8);
    t0=NOW; for(u64 i=0;i<N;i++) t8_insert(&A,ptr[i]); t1=NOW; double rA=ns(t0,t1,N);
    t0=NOW; for(u64 i=0;i<N;i++) t16_insert(&L,ptr[i],0); t1=NOW; double rD=ns(t0,t1,N);
    t0=NOW; for(u64 i=0;i<N;i++) t8_insert(&Et,ptr[i]); t1=NOW; double rE=ns(t0,t1,N);
    printf("%-34s %9.1f %9.1f %9.1f\n","register",rA,rD,rE);
    t0=NOW; for(u64 k=0;k<nref;k++){ u64 i=refd[k]; u64 j=t16_find(&L,ptr[i]); u64 id=next_id++; L.e[j].v=id; ids[i]=id; t16_insert(&I,id,ptr[i]); } t1=NOW;
    double aD=nref?ns(t0,t1,nref):0.0;
    t0=NOW; for(u64 k=0;k<nref;k++){ u64 i=refd[k]; u64 j=t8t_find(&Et,ptr[i]); u64 id=next_id++; Et.k[j]|=1; idsE[i]=id; t16_insert(&Ea,ptr[i],id); t16_insert(&Ei,id,ptr[i]); } t1=NOW;
    printf("%-34s %9s %9.1f %9.1f\n","first `ref x` (assign id)","free",aD,nref?ns(t0,t1,nref):0.0);
    t0=NOW; for(u64 k=0;k<nref;k++){ u64 i=refd[k]; u64 j=t16_find(&L,ptr[i]); sink+=L.e[j].v; } t1=NOW;
    double bD=nref?ns(t0,t1,nref):0.0;
    t0=NOW; for(u64 k=0;k<nref;k++){ u64 i=refd[k]; u64 j=t8t_find(&Et,ptr[i]); sink+=Et.k[j]&1; u64 j2=t16_find(&Ea,ptr[i]); sink+=Ea.e[j2].v; } t1=NOW;
    printf("%-34s %9s %9.1f %9.1f\n","later `ref x` (lookup id)","free",bD,nref?ns(t0,t1,nref):0.0);
    t0=NOW; for(u64 k=0;k<nref;k++){ u64 i=refd[k]; u64 j=t16_find(&I,ids[i]); sink+=I.e[j].v; } t1=NOW; double lD=nref?ns(t0,t1,nref):0.0;
    t0=NOW; for(u64 k=0;k<nref;k++){ u64 i=refd[k]; sink+=t8_find(&A,ptr[i])!=~0ULL; } t1=NOW; double lA=nref?ns(t0,t1,nref):0.0;
    t0=NOW; for(u64 k=0;k<nref;k++){ u64 i=refd[k]; u64 j=t16_find(&Ei,idsE[i]); sink+=Ei.e[j].v; } t1=NOW; double lE=nref?ns(t0,t1,nref):0.0;
    printf("%-34s %9.1f %9.1f %9.1f\n","match Some: resolve live",lA,lD,lE);
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; t8_remove(&A,ptr[i]); } t1=NOW; double fA=ns(t0,t1,N);
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; u64 j=t16_find(&L,ptr[i]); u64 id=L.e[j].v; t16_remove_at(&L,j); if(id){ u64 j2=t16_find(&I,id); t16_remove_at(&I,j2); } } t1=NOW; double fD=ns(t0,t1,N);
    t0=NOW; for(u64 k=0;k<N;k++){ u64 i=order[k]; u64 j=t8t_find(&Et,ptr[i]); int tag=Et.k[j]&1; t8t_remove_at(&Et,j); if(tag){ u64 j1=t16_find(&Ea,ptr[i]); u64 id=Ea.e[j1].v; t16_remove_at(&Ea,j1); u64 j2=t16_find(&Ei,id); t16_remove_at(&Ei,j2); } } t1=NOW; double fE=ns(t0,t1,N);
    printf("%-34s %9.1f %9.1f %9.1f\n","free (all objects, random order)",fA,fD,fE);
    // steady-state churn on a fresh half-size live set: free a random live object and register a new one at the same address (as malloc reuses it)
    { u64 live=N/2; for(u64 i=0;i<N;i++){ if(i<live){ t8_insert(&A,ptr[i]); t16_insert(&L,ptr[i],0); t8_insert(&Et,ptr[i]); } }
      u64 R=N;
      t0=NOW; for(u64 r=0;r<R;r++){ u64 s=rnd()%live; t8_remove(&A,ptr[s]); t8_insert(&A,ptr[s]); } t1=NOW; double cA=ns(t0,t1,R);
      t0=NOW; for(u64 r=0;r<R;r++){ u64 s=rnd()%live; u64 j=t16_find(&L,ptr[s]); t16_remove_at(&L,j); t16_insert(&L,ptr[s],0); } t1=NOW; double cD=ns(t0,t1,R);
      t0=NOW; for(u64 r=0;r<R;r++){ u64 s=rnd()%live; u64 j=t8t_find(&Et,ptr[s]); t8t_remove_at(&Et,j); t8_insert(&Et,ptr[s]); } t1=NOW; double cE=ns(t0,t1,R);
      printf("%-34s %9.1f %9.1f %9.1f\n","steady churn (no refs taken)",cA,cD,cE); }
    (void)sink;
}

int main(int argc,char**argv){
    if(argc>1 && argv[1][0]=='L'){ u64 N = argc>2? strtoull(argv[2],0,10):100000; double fs[]={0,0.1,1}; for(int k=0;k<3;k++) runLazy(N,fs[k]); return 0; }
    u64 sizes[]={1000,100000,1000000,4000000};
    int which = argc>1 ? atoi(argv[1]) : -1;
    for(int s=0;s<4;s++){ if(which>=0 && which!=s) continue; run(sizes[s],1); }
    return 0;
}
