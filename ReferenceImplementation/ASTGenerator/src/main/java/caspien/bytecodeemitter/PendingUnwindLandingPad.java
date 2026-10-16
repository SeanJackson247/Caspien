package caspien.bytecodeemitter;

import caspien.token.Token;
import java.util.List;

/** One buffered call-site unwind landing pad -- see `pendingCallSiteUnwindLandingPads`'s own doc comment. */
public final class PendingUnwindLandingPad {
    public final String label;
    public final List<String> destructNames;
    public final List<Token> unlockCalls;
    public final Token at;

    public PendingUnwindLandingPad(String label, List<String> destructNames, List<Token> unlockCalls, Token at) {
        this.label = label;
        this.destructNames = destructNames;
        this.unlockCalls = unlockCalls;
        this.at = at;
    }
}
