package nok.ui;

/**
 * Callback for {@link UiSymbols}: receives the character the user picked.
 *
 * <p>Mirrors UiMenuOwner / UiDialogOwner / UiInputOwner: the popup owns no
 * state of its own and hands its single result back to the screen that opened
 * it. There is deliberately no cancel callback - a dismissed picker is
 * indistinguishable from one that was never opened, so owners need no cancel
 * branch.
 *
 * <p>Called on the LCDUI thread, after the picker has already restored the
 * owning screen, so an implementation is free to navigate elsewhere.
 */
public interface UiSymbolOwner {

    /** The chosen character; insert it wherever the owner's caret is. */
    void symbolPicked(char c);
}
