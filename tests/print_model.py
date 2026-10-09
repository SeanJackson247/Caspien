#!/usr/bin/env python3
"""Expected output of tests/print_test.caspien, written from the language rules (decimal ints, %g floats, true/false), not from the program."""
vals = [200, 65000, 4000000000, 18446744073709551615, -100, -30000, -2000000000, -9223372036854775807]
out = ["text|line", "a String|a String"] + [str(v) for v in vals]
out += ["%g" % 1.5, "%g" % 0.1, "true", "false", "Z", "7"]
print("\n".join(out))
