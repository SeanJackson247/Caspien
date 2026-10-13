package caspien.typechecker;

import java.util.ArrayList;
import java.util.List;

public class EnumInfo {
    public final String name;
    public final List<String> variants = new ArrayList<>();
    /** Decorated @non_exhaustive: the enum must end with a `default` case. */
    public boolean isNonExhaustive;
    /** Explicit integer discriminants; null unless every variant has one (no partial mixing). Also the test for a "guaranteed" enum (checkGuaranteedEnumMembership). */
    public List<Long> explicitValues;
    /** Explicit [start, end] range per variant for a range-valued enum; null otherwise, exclusive with `explicitValues`. */
    public List<Long> explicitRangeStarts;
    public List<Long> explicitRangeEnds;
    /** True when this enum is range-valued. */
    public boolean isRangeValued() { return explicitRangeStarts != null; }
    public EnumInfo(String name) { this.name = name; }
}
