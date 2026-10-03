// LD_PRELOAD shim for the *_oom_check.sh scripts: FAILN=n makes the nth malloc return NULL; FAILREALLOC=size makes the first realloc
// of that size fail (every one of them when FAILREALLOC_STICKY is set, like a real out-of-memory that persists); prints ALLOCS (mallocs made) and LIVE (allocations not freed) to stderr at exit.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
static long n=0, failn=-1, live=0, failrealloc=-1; static int rfailed=0, sticky=0;
static void *(*rm)(size_t); static void *(*rr)(void*,size_t); static void (*rf)(void*);
static void init(void){ if(!rm){ rm=dlsym(RTLD_NEXT,"malloc"); rr=dlsym(RTLD_NEXT,"realloc"); rf=dlsym(RTLD_NEXT,"free");
  const char*e=getenv("FAILN"); if(e) failn=atol(e); e=getenv("FAILREALLOC"); if(e) failrealloc=atol(e); if(getenv("FAILREALLOC_STICKY")) sticky=1;} }
void *malloc(size_t s){ init(); n++; if(failn>0 && n==failn) return NULL; void*p=rm(s); if(p) live++; return p; }
void *realloc(void*p,size_t s){ init(); if(failrealloc>0 && !rfailed && (long)s==failrealloc){ rfailed=!sticky; return NULL; } void*q=rr(p,s); if(!p&&q) live++; return q; }
void free(void*p){ init(); if(p) live--; rf(p); }
__attribute__((destructor)) static void fin(void){ fprintf(stderr,"ALLOCS=%ld LIVE=%ld\n",n,live); }
