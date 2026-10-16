package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import java.util.HashMap;
import java.util.Map;

final class StructMemberReorderingSite {
    int start;
    int end;                         // exclusive
    StructMemberReorderingDecl decl;
    Map<String, BytecodeProgram> units = new HashMap<>();   // member name -> its lines
}
