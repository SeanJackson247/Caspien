package caspien.postpasses;

/**
 * The one place that says how the auditing and debugging tools colour things (`--viz` today, anything graphical later), so they all agree.
 * Nothing prints this to the user: it is the look of the tools.
 *
 * Termination class (`bound | finite | unbound | none | conditional | indirect`), drawn on a dark background. The hue follows how strong the guarantee
 * is: blue = it ends and the cost is known, purple = it ends, red = no guarantee. `none` (always running, with certainty) is a vivid amber rather than
 * white (white reads as "empty" and vanishes on light themes), `conditional` is grey, and `indirect` ("cannot see inside") is a dark disc with a bright
 * outline. Colour never carries the class alone: each class also has its own outline style, for readers who cannot tell the hues apart.
 *
 * Order, weakest guarantee last, is the audit's own worst-class order: bound < finite < indirect < unbound < conditional < none. Red means "not guaranteed
 * to end", not "wrong": a `none` loop in an event loop is intended, which is why it has a colour of its own.
 */
public final class Palette {
    public Palette() {
    }

    /** class, fill, outline colour, outline style (solid | double | dashed | thick | dotted | dashdot). */
    public static final String[][] TERMINATION = {
        {"bound",       "#3b82f6", "#3b82f6", "solid"},
        {"finite",      "#8b5cf6", "#8b5cf6", "double"},
        {"indirect",    "#0a0a0a", "#e5e7eb", "dashdot"},
        {"unbound",     "#ef4444", "#ef4444", "dashed"},
        {"conditional", "#9ca3af", "#9ca3af", "dotted"},
        {"none",        "#fbbf24", "#fbbf24", "thick"},
    };

    /** The table as a JSON object for a page: {"bound":{"fill":..,"line":..,"ring":..}, ...}. */
    public static String terminationJson() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < TERMINATION.length; i++) {
            String[] t = TERMINATION[i];
            sb.append(i == 0 ? "" : ",").append('"').append(t[0]).append("\":{\"fill\":\"").append(t[1]).append("\",\"line\":\"").append(t[2])
                    .append("\",\"ring\":\"").append(t[3]).append("\"}");
        }
        return sb.append('}').toString();
    }
}
