package nok.ui;

import nok.NoksidianMIDlet;

/**
 * Thin subclass of the themed {@link UiEditor}, preserved so the historical
 * constructor contract {@code Editor(NoksidianMIDlet, String, String)} still
 * resolves for any caller compiled against it. All editor behavior (the
 * custom Canvas editor honoring nok.ui.Theme) lives in {@link UiEditor}.
 *
 * <p>Nothing in the app constructs this class: NoksidianMIDlet.editNote builds a
 * UiEditor directly, which is the option CONTRACTS-UI.md marks as preferred, and
 * this file is the "keep Editor.java as a thin subclass for contract stability"
 * half of the same note. build.sh compiles it (it globs src for *.java) but
 * ProGuard shrinking, rooted only at nok.NoksidianMIDlet, drops it again, so it
 * costs nothing in the shipped jar -- do not expect nok/ui/Editor.class to be
 * there.
 *
 * <p>Worth knowing before touching either file: CONTRACTS.md still describes
 * nok.ui.Editor as {@code extends TextBox implements CommandListener}. That
 * contract is historical. The themed-UI rewrite removed the native TextBox editor
 * on purpose, so only the constructor signature survived the port, not the
 * supertype. CONTRACTS-UI.md declares UiEditor final, but the shipped class is not
 * final precisely so this subclass can exist. Restoring that final means deleting
 * this file, not reintroducing a TextBox.
 *
 * <p>Argument semantics are inherited wholesale from UiEditor: rel is a
 * vault-relative path with '/' separators, and content is already-decrypted
 * plaintext, since readText resolves the NKE1 layer before any editor is
 * constructed. Oversized content is not an error here either -- UiEditor latches
 * it and routes the user to the read-only viewer.
 */
public final class Editor extends UiEditor {

    public Editor(NoksidianMIDlet m, String rel, String content) {
        super(m, rel, content);
    }
}
