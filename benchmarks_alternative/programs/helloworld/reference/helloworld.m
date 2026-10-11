// Hello World (Objective-C). Prints one line; N (argv[1]) is accepted and ignored. Measures startup, compile time and binary size.
#include <objc/objc.h>
#include <objc/runtime.h>
#pragma GCC diagnostic ignored "-Wobjc-root-class"
// The benchmark body lives in a class method of a small root class (GNU libobjc ships no Foundation / NSObject here).
@interface Runner { Class isa; }
+ (int)runWithArgc:(int)argc argv:(char **)argv;
@end
#include <stdio.h>
@implementation Runner
+ (int)runWithArgc:(int)argc argv:(char **)argv {
    (void)argc; (void)argv;
    printf("Hello, World!\n");
    return 0;
}
@end
int main(int argc, char **argv) { return [Runner runWithArgc:argc argv:argv]; }
