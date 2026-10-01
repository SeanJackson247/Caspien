// Binary trees (Benchmarks Game style): ordinary heap objects with left/right object references, one object per node.
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// GNU libobjc ships no Foundation / NSObject here, so every class derives from this tiny root class (instances are heap objects
// created with class_createInstance, i.e. one allocation per object, released with object_dispose).
@interface Base { Class isa; }
+ (id)new;
- (void)free;
@end
@implementation Base
+ (id)new { return class_createInstance(self, 0); }
- (void)free { object_dispose(self); }
@end

#include <stdio.h>
#include <stdlib.h>

@interface Node : Base { @public Node *l, *r; }
+ (Node *)make:(int)d;
- (long)check;
- (void)release;
@end
@implementation Node
+ (Node *)make:(int)d {
    Node *n = [Node new];
    if (d > 0) { n->l = [Node make:d - 1]; n->r = [Node make:d - 1]; }
    else { n->l = nil; n->r = nil; }
    return n;
}
- (long)check { return l ? 1 + [l check] + [r check] : 1; }
- (void)release {
    if (l) { [l release]; [r release]; }
    [self free];
}
@end

int main(int argc, char **argv) {
    int maxd = argc > 1 ? atoi(argv[1]) : 16;
    int d;
    long i, iters, sum;
    Node *t, *longlived, *a;
    if (maxd < 6) maxd = 6;
    t = [Node make:maxd + 1];
    printf("%ld", [t check]);
    [t release];
    longlived = [Node make:maxd];
    for (d = 4; d <= maxd; d += 2) {
        iters = 1L << (maxd - d + 4); sum = 0;
        for (i = 0; i < iters; i++) { a = [Node make:d]; sum += [a check]; [a release]; }
        printf(" %ld", sum);
    }
    printf(" %ld\n", [longlived check]);
    [longlived release];
    return 0;
}
