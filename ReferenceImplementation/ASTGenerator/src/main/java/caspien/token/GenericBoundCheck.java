package caspien.token;

/** One entry of `pendingGenericBoundChecks` -- see its own doc comment. */
public final class GenericBoundCheck {
    public final String typeParamName;
    public final String concreteTypeName;
    public final String interfaceName;
    public GenericBoundCheck(String typeParamName, String concreteTypeName, String interfaceName) {
        this.typeParamName = typeParamName;
        this.concreteTypeName = concreteTypeName;
        this.interfaceName = interfaceName;
    }
}
