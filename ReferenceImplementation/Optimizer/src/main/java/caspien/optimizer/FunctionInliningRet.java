package caspien.optimizer;

final class FunctionInliningRet {
    int idx;
    boolean isVoid;
    String type;
    int vStart = -1, vTail = -1;
    boolean pure;
    boolean tail;
}
