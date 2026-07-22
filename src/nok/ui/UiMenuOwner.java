package nok.ui;

/**
 * Callback for a {@link UiMenu} selection. The menu returns to its back
 * screen before invoking this, so the owner is free to navigate elsewhere.
 *
 * <p>Its own interface rather than Command/CommandListener because UiMenu
 * replaces the platform Options menu outright: the items are plain String[]
 * labels with no Command objects behind them. CLDC 1.1 has no closures, so
 * screens implement this directly (Library, Settings, UiEditor, UiList,
 * VaultPicker) or, for one-off menus, pass a small anonymous class (Viewer).</p>
 *
 * <p>The label is the command's identity: no implementor uses index to decide
 * what to run, they match item instead (UiList does not even do that -- it
 * forwards the bare label on as UiListOwner.listMenu). That is what lets a menu
 * be declared as a String[] literal, but it also means the labels within one
 * menu must be distinct and must stay character-for-character in step with the
 * array handed to the UiMenu constructor.</p>
 *
 * <p>index is the row in that same array, but it is not guaranteed to be a real
 * row: Library.listMenu(String) forwards to its own menuSelect with index -1 for
 * a command that did not come from a popup row. Treat index as a hint, not as a
 * safe array subscript.</p>
 *
 * <p>There is deliberately no cancel callback -- a dismissed menu is silent
 * and indistinguishable from one that was never opened, so owners need no
 * cancel branch. This runs on the single LCDUI event thread, from UiMenu's key
 * handler, which is why no implementor synchronizes; long work here would
 * stall painting.</p>
 */
public interface UiMenuOwner {
    /** A menu item was chosen (FIRE / left-soft). index is its row. */
    void menuSelect(String item, int index);
}
