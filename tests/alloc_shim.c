// LD_PRELOAD shim for the *_oom_check.sh scripts: FAILN=n makes the nth malloc return NULL; FAILREALLOC=size makes the first realloc
// of that size fail (every one of them when FAILREALLOC_STICKY is set, like a real out-of-memory that persists); FAILPTHREAD=1 makes pthread_create fail with EAGAIN; prints ALLOCS (mallocs made) and LIVE (allocations not freed) to stderr at exit.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <pthread.h>
static long n=0, failn=-1, live=0, failrealloc=-1; static int rfailed=0, sticky=0;
static void *(*rm)(size_t); static void *(*rr)(void*,size_t); static void (*rf)(void*);
static void init(void){ if(!rm){ rm=dlsym(RTLD_NEXT,"malloc"); rr=dlsym(RTLD_NEXT,"realloc"); rf=dlsym(RTLD_NEXT,"free");
  const char*e=getenv("FAILN"); if(e) failn=atol(e); e=getenv("FAILREALLOC"); if(e) failrealloc=atol(e); if(getenv("FAILREALLOC_STICKY")) sticky=1;} }
void *malloc(size_t s){ init(); n++; if(failn>0 && n==failn) return NULL; void*p=rm(s); if(p) live++; return p; }
void *realloc(void*p,size_t s){ init(); if(failrealloc>0 && !rfailed && (long)s==failrealloc){ rfailed=!sticky; return NULL; } void*q=rr(p,s); if(!p&&q) live++; return q; }
void free(void*p){ init(); if(p) live--; rf(p); }
/* FAILPTHREAD=1: pthread_create reports EAGAIN (11) without starting a thread. */
int pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*f)(void*), void *arg){
  static int (*rp)(pthread_t*,const pthread_attr_t*,void*(*)(void*),void*);
  if(getenv("FAILPTHREAD")) return 11;
  if(!rp) rp=dlsym(RTLD_NEXT,"pthread_create");
  return rp(t,a,f,arg); }
__attribute__((destructor)) static void fin(void){ fprintf(stderr,"ALLOCS=%ld LIVE=%ld\n",n,live); }
