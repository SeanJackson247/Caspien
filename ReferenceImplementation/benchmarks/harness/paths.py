"""Where things are. Everything else imports its paths from here."""
import os

HARNESS = os.path.dirname(os.path.abspath(__file__))
BENCH = os.path.dirname(HARNESS)                      # the benchmarks folder
REPO = os.environ.get("CASPIEN_REPO") or os.path.dirname(BENCH)   # the Caspien checkout (compiler, stdlib, tests); default: the folder this one sits in
PROGRAMS = os.path.join(BENCH, "programs")
MANIFEST = os.path.join(PROGRAMS, "programs.json")
SPECIFIC = os.path.join(HARNESS, "specific.json")     # per-program Caspien switch overrides
DATA = os.path.join(BENCH, "site", "data")            # results are published here, next to the site that shows them
BUILD_CACHE = os.path.join(BENCH, ".build_cache")     # other languages' builds, keyed on sources + command + compiler
LEAK_LOG = os.environ.get("BENCH_LEAK_LOG", os.path.join(BENCH, ".leaks.log"))
