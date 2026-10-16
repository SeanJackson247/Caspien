#!/usr/bin/env python3
"""Expected output of tests/print_readme_test.caspien, written from the language rules (break leaves the innermost loop, `&&` evaluates both
sides, `&&then` only the left when it is false, top = (x+1)*2, rejected above 100)."""
out = ["Hello World!"]
out += ["i=%d" % i for i in range(4)]
out += ["i=%d j=0" % i for i in range(3)]
out += ["  called a", "  called b", "  called a"]          # `&&` calls both, `&&then` stops at the false left side
def run(x):
    if x > 100:
        out.append("middle: saw 'too big', passing it on")
        out.append("run(%d): caught 'top: request rejected'" % x)
    else:
        out.append("run(%d): ok, r=%d" % (x, (x + 1) * 2))
    out.append("run(%d): done" % x)
run(5); run(500)
print("\n".join(out))
