// LD_PRELOAD shim for the *_oom_check.sh scripts: FAILN=n makes the nth malloc return NULL; FAILREALLOC=size makes the first realloc
// of that size fail (every one of them when FAILREALLOC_STICKY is set, like a real out-of-memory that persists); FAILPTHREAD=1 makes pthread_create fail with EAGAIN; prints ALLOCS (mallocs made), LIVE (allocations not freed) and REALLOCS (realloc calls) to stderr at exit; GT_ADDR=<address of the program's `ghost_table` global, from `nm`> and GT_EXE=<its path> add GT_LEN (other processes that inherit the preload, e.g. a `popen` child, print nothing), the ghost table's entry count at exit (blocks still registered = never freed; 0 for a program that cleans up after itself). The counters are atomic, so threads (`par`) are counted correctly.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>
static long nre=0, n=0, failn=-1, live=0, failrealloc=-1; static int rfailed=0, sticky=0;
static void *(*rm)(size_t); static void *(*rr)(void*,size_t); static void (*rf)(void*);
static void init(void){ if(!rm){ rm=dlsym(RTLD_NEXT,"malloc"); rr=dlsym(RTLD_NEXT,"realloc"); rf=dlsym(RTLD_NEXT,"free");
  const char*e=getenv("FAILN"); if(e) failn=atol(e); e=getenv("FAILREALLOC"); if(e) failrealloc=atol(e); if(getenv("FAILREALLOC_STICKY")) sticky=1;} }
void *malloc(size_t s){ init(); long k=__atomic_add_fetch(&n,1,__ATOMIC_RELAXED); if(failn>0 && k==failn) return NULL; void*p=rm(s); if(p) __atomic_add_fetch(&live,1,__ATOMIC_RELAXED); return p; }
void *realloc(void*p,size_t s){ init(); __atomic_add_fetch(&nre,1,__ATOMIC_RELAXED); if(failrealloc>0 && !rfailed && (long)s==failrealloc){ rfailed=!sticky; return NULL; } void*q=rr(p,s); if(!p&&q) __atomic_add_fetch(&live,1,__ATOMIC_RELAXED); return q; }
void free(void*p){ init(); if(p) __atomic_sub_fetch(&live,1,__ATOMIC_RELAXED); rf(p); }
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
  if(g){ unsigned long a=strtoul(g,NULL,16); fprintf(stderr,"ALLOCS=%ld LIVE=%ld REALLOCS=%ld GT_LEN=%lu\n",n,live,nre,*(unsigned long*)(a+24)); }
  else fprintf(stderr,"ALLOCS=%ld LIVE=%ld REALLOCS=%ld\n",n,live,nre); }
