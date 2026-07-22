package nok.ui;

/**
 * Callback for a {@link UiDialog}. positive is true for OK / Yes, false for
 * No / Cancel. The owner is responsible for navigating away (the built-in
 * {@link UiDialog#info} helper just re-shows the back screen).
 *
 * <p>MIDP has no modal "show this and block until the user answers" call -
 * Display.setCurrent returns immediately - so a confirmation cannot be an
 * if-statement. Every prompt is split in two: the code that opens the dialog,
 * and this callback that resumes once a key arrives. That is why the whole app
 * asks questions through a one-method listener rather than a boolean-returning
 * helper.</p>
 *
 * <p>Implemented two ways depending on how much state the answer needs: Settings
 * has a single prompt and implements the interface directly, while anything that
 * must remember what the answer applies to uses a named inner class or an
 * anonymous one that captures it (Library's DeleteOwner holds the row path,
 * UiEditor and the confirm flows in NoksidianMIDlet use anonymous owners).</p>
 */
public interface UiDialogOwner {
    /**
     * A dialog button was pressed. The positive answer is produced by the left
     * soft key, the middle soft key and FIRE alike. A {@link UiDialog#OK} dialog
     * never reports false: its right soft key also dismisses it positively, so do
     * not hang cleanup off the false branch of an OK-kind prompt.
     *
     * <p>Runs on the LCDUI thread inside keyPressed, so it must return quickly;
     * an owner with real work to do sets a status line and hands the slow part to
     * a Thread (Library's DeleteOwner is the example). UiDialog only re-shows its
     * back screen itself when there is no owner, so an owner MUST navigate
     * somewhere - even on cancel, where the usual move is to re-show the screen
     * underneath. Forgetting to do so leaves the modal up with no way out of it.</p>
     */
    void dialogResult(boolean positive);
}
