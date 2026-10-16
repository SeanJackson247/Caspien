package caspien.optimizer;

import caspien.bytecode.BytecodeProgram;
import java.util.ArrayList;
import java.util.List;

final class StructMemberReorderingDecl {
    String name;
    int start;                       // index of STRUCT_START
    int end;                         // index of STRUCT_END
    BytecodeProgram head = new BytecodeProgram();   // STRUCT_START, STRUCT_DECORATE..., the ___type member
    List<StructMemberReorderingEntry> entries = new ArrayList<>();              // everything after ___type, in order
    boolean hasType;
    boolean typeFirst;
    boolean plain = true;            // scalar members only, no odd decorators
    List<StructMemberReorderingEntry> members = new ArrayList<>();
    long classId = -1;
    boolean blocked;
    List<StructMemberReorderingEntry> newEntries;          // the reordered layout (members and padding)
}
