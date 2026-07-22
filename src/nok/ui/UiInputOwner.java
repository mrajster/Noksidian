package nok.ui;

/**
 * Callback for a {@link UiInput} single-line prompt.
 *
 * <p>It is a callback rather than a return value because MIDP has no modal
 * input: UiInput is a Displayable of its own, so showing it returns at once
 * and the typed answer can only arrive later. Whatever the answer has to be
 * applied to must therefore be held somewhere across that round trip, and
 * each caller picks its own carrier: Settings implements this interface on
 * the screen and remembers editingRow, Library uses a per-prompt PromptOwner
 * holding the P_* mode captured when the prompt went up (sel and the row
 * model may both have moved on by the time the user replies),
 * CryptoSetup.Flow is a state machine that chains one masked field into the
 * next, and NoksidianMIDlet's unlock prompt uses an anonymous owner.</p>
 *
 * <p>The value handed to inputOk is never null but is otherwise raw: UiInput
 * applies no length cap and no character filtering, so trimming and
 * validation belong to the owner (a file name, a PAT and a password share no
 * rule worth enforcing in the widget). Library and VaultPicker read an empty
 * or all-blank reply as a cancel rather than an error, since OK on an
 * untouched field is a common keypad misfire; Settings and the password
 * flows take the value verbatim instead. In masked mode the value is still
 * the real plaintext, not the bullets that were drawn.</p>
 *
 * <p>Navigation is the owner's duty. UiInput replaced the caller's screen and
 * never puts it back, so every path out of both methods has to show
 * something: the previous screen, a dialog, or the next field of a chain.
 * Until it does, the prompt stays current and live and a second OK press
 * simply re-enters inputOk.</p>
 *
 * <p>Both methods run on the MIDP UI thread, dispatched from inside
 * UiScreen.keyPressed, so an implementation with real work to do (a vault
 * re-encrypt, a network fetch) must hand it to a worker instead of blocking
 * the paint loop.</p>
 */
public interface UiInputOwner {
    /** Right-soft "OK" / FIRE / Enter. */
    void inputOk(String value);

    /** Left-soft "Cancel". */
    void inputCancel();
}
