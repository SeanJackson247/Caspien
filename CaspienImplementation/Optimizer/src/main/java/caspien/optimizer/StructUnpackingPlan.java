package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class StructUnpackingPlan {
    final Set<Integer> remove = new HashSet<>();
    final Map<Integer, BytecodeProgram> replace = new HashMap<>();
    final Map<Integer, String> rename = new HashMap<>();
}
