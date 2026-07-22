package nok.ui;

/**
 * Callback contract for a UiList's host screen (e.g. Library).
 *
 * UiList is a hand-painted Canvas rather than an lcdui List, so there is no
 * CommandListener and no platform selection event to hang navigation off. This
 * interface is that missing wiring: the widget keeps painting, scrolling and
 * key decoding, and hands back the three decisions it cannot make - what a row
 * means, what a command does, and where "back" goes.
 *
 * Java 1.3 source level, so no default methods: every owner implements all
 * three even when it only cares about one. The only owners actually handed to
 * a UiList are the throwaway pickers (search results in NoksidianMIDlet, the
 * "Links" list in Viewer), anonymous inner classes whose listMenu and listBack
 * are both just "return to the previous screen". Library also declares the
 * interface, but it extends UiScreen and paints its own rows rather than
 * hosting a UiList, and its own key handlers call openSelected directly, so
 * its three methods currently have no caller in the app.
 *
 * Contract details that are not visible from the signatures:
 * - Everything here is called on the LCDUI thread, from inside key handling.
 *   Implementations may navigate away (Display.setCurrent) before returning;
 *   UiMenu has already re-shown the list by the time listMenu runs, so an owner
 *   that shows another screen is not racing a pending restore.
 * - listSelect's index is bounds-checked by UiList against its own label Vector
 *   before the call, but that Vector is aliased rather than copied and an owner
 *   may index a parallel model instead (Viewer's list is built from a labels
 *   Vector parallel to the MdSpan Vector it indexes). Both anonymous owners
 *   re-check the range themselves rather than trust the widget's view of size.
 * - listMenu receives only the label, never an index, so the strings passed to
 *   UiList.setMenu must be distinct within one list and owners match them with
 *   equals. "Direct command" means an owner invoking it itself for a command
 *   that never appeared in a popup - Library's implementation forwards to
 *   menuSelect(command, -1) for exactly that case.
 * - listBack fires only when the right soft caption is literally "Back"; any
 *   other caption, including none at all, activates the row instead. Doing
 *   nothing is a legitimate implementation (Library ignores it at the root).
 * - UiList tolerates a null owner, so a purely display-only list needs no
 *   implementation of this at all.
 */
public interface UiListOwner {

    /** Row activated (FIRE / right-soft Open / enter). */
    void listSelect(int index);

    /** A menu command chosen (from setMenu) or a direct command. */
    void listMenu(String command);

    /** Clear / back (right-soft when its caption is "Back"). */
    void listBack();
}
