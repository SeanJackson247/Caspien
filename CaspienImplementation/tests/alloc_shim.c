// LD_PRELOAD shim for the *_oom_check.sh scripts: FAILN=n makes the nth malloc return NULL; FAILREALLOC=size makes the first realloc
// of that size fail (every one of them when FAILREALLOC_STICKY is set, like a real out-of-memory that persists); FAILPTHREAD=1 makes pthread_create fail with EAGAIN; prints ALLOCS (mallocs made), LIVE (allocations not freed) and REALLOCS (realloc calls) to stderr at exit; GT_ADDR=<address of the program's `ghost_table` global, from `nm`> and GT_EXE=<its path> add GT_LEN (other processes that inherit the preload, e.g. a `popen` child, print nothing), the ghost table's entry count at exit (blocks still registered = never freed; 0 for a program that cleans up after itself). PEAK=<bytes> is the peak of live program-requested bytes (malloc sizes, realloc moves them; the ghost table's own blocks -- the first malloc(64) of gt_init and every realloc(NULL, ..) of gt_register/gt_destruct -- are not counted). The counters are atomic, so threads (`par`) are counted correctly.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
#include <link.h>
static long nre=0, n=0, failn=-1, live=0, failrealloc=-1, curb=0, peakb=0, gtfirst=1; static pthread_mutex_t bm=PTHREAD_MUTEX_INITIALIZER;
#define MAXB 4194304
static struct{void*p;size_t s;} blk[MAXB]; /* open-addressing table of live blocks (size known at free/realloc) */
static unsigned hsh(void*p){ return (unsigned)(((unsigned long)p>>4)*2654435761u)&(MAXB-1); }
static void bput(void*p,size_t s){ unsigned h=hsh(p); while(blk[h].p&&blk[h].p!=(void*)1&&blk[h].p!=p) h=(h+1)&(MAXB-1); blk[h].p=p; blk[h].s=s; }
static long bdel(void*p){ unsigned h=hsh(p); while(blk[h].p){ if(blk[h].p==p){ long s=blk[h].s; blk[h].p=(void*)1; return s; } h=(h+1)&(MAXB-1);} return -1; }
static void bup(long d){ curb+=d; if(curb>peakb) peakb=curb; } static int rfailed=0, sticky=0;
static void *(*rm)(size_t); static void *(*rr)(void*,size_t); static void (*rf)(void*);
static unsigned long tlo=0, thi=0;
static int phcb(struct dl_phdr_info*i,size_t sz,void*d){ if(i->dlpi_name&&i->dlpi_name[0]) return 0; for(int k=0;k<i->dlpi_phnum;k++){ const ElfW(Phdr)*h=&i->dlpi_phdr[k]; if(h->p_type==PT_LOAD&&(h->p_flags&PF_X)){ tlo=i->dlpi_addr+h->p_vaddr; thi=tlo+h->p_memsz; } } return 1; }
#define FROM_EXE(ra) ((unsigned long)(ra)>=tlo&&(unsigned long)(ra)<thi)
static void init(void){ if(!rm){ dl_iterate_phdr(phcb,NULL); rm=dlsym(RTLD_NEXT,"malloc"); rr=dlsym(RTLD_NEXT,"realloc"); rf=dlsym(RTLD_NEXT,"free");
  const char*e=getenv("FAILN"); if(e) failn=atol(e); e=getenv("FAILREALLOC"); if(e) failrealloc=atol(e); if(getenv("FAILREALLOC_STICKY")) sticky=1;} }
void *malloc(size_t s){ init(); void*ra=__builtin_return_address(0); long k=__atomic_add_fetch(&n,1,__ATOMIC_RELAXED); if(failn>0 && k==failn) return NULL; void*p=rm(s); if(p) __atomic_add_fetch(&live,1,__ATOMIC_RELAXED);
  if(p&&FROM_EXE(ra)){ pthread_mutex_lock(&bm); if(gtfirst&&s==64) gtfirst=0; else { bput(p,s); bup((long)s); } pthread_mutex_unlock(&bm); } return p; }
void *realloc(void*p,size_t s){ init(); void*ra=__builtin_return_address(0); __atomic_add_fetch(&nre,1,__ATOMIC_RELAXED); if(failrealloc>0 && !rfailed && (long)s==failrealloc){ rfailed=!sticky; return NULL; } void*q=rr(p,s); if(!p&&q) __atomic_add_fetch(&live,1,__ATOMIC_RELAXED);
  if(q&&p&&FROM_EXE(ra)){ pthread_mutex_lock(&bm); long o=bdel(p); if(o>=0){ bup((long)s); bup(-o); bput(q,s); } pthread_mutex_unlock(&bm); } return q; }
void free(void*p){ init(); if(p){ __atomic_sub_fetch(&live,1,__ATOMIC_RELAXED); pthread_mutex_lock(&bm); long o=bdel(p); if(o>=0) bup(-o); pthread_mutex_unlock(&bm); } rf(p); }
/* FAILPTHREAD=1: pthread_create reports EAGAIN (11) without starting a thread. */
int pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*f)(void*), void *arg){
  static int (*rp)(pthread_t*,const pthread_attr_t*,void*(*)(void*),void*);
  if(getenv("FAILPTHREAD")) return 11;
  if(!rp) rp=dlsym(RTLD_NEXT,"pthread_create");
  return rp(t,a,f,arg); }
/* ghost_table = { ___type, lockState, base, len, capacity }, 8 bytes each: len is the fourth word. */
__attribute__((destructor)) static void fin(void){
  const char*g=getenv("GT_ADDR"), *x=getenv("GT_EXE");
  if(g && x){ char me[4096]; ssize_t k=readlink("/proc/self/exe",me,sizeof me-1); if(k<0) return; me[k]=0;
    char want[4096]; if(!realpath(x,want) || strcmp(me,want)) return; }
  if(g){ unsigned long a=strtoul(g,NULL,16); fprintf(stderr,"ALLOCS=%ld LIVE=%ld REALLOCS=%ld GT_LEN=%lu PEAK=%ld\n",n,live,nre,*(unsigned long*)(a+24),peakb); }
  else fprintf(stderr,"ALLOCS=%ld LIVE=%ld REALLOCS=%ld PEAK=%ld\n",n,live,nre,peakb); }
