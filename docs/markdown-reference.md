# Noksidian Markdown Reference

This page lists **every markdown construct Noksidian's parser understands**, with an example
and a note on how it looks on the phone's screen. Noksidian speaks the Obsidian flavor of
markdown — the same files render in desktop Obsidian and on the E71 — but it is a hand-written
parser on a 2008 phone, so it is a deliberate subset. The [last section](#not-supported)
spells out exactly what is *not* supported and what happens when you use it anyway.

Everything is parsed and rendered on the phone itself, on a 320x240 canvas, from plain UTF-8
`.md` files. For how to *navigate* rendered notes (scrolling, the link focus model, creating
notes from wikilinks), see [Reading notes in the user guide](user-guide.md#reading-notes).

## Contents

- [Rendering at a glance](#rendering-at-a-glance)
- [Block elements](#block-elements)
  - [Headings](#headings)
  - [Paragraphs and line breaks](#paragraphs-and-line-breaks)
  - [Horizontal rules](#horizontal-rules)
  - [Blockquotes](#blockquotes)
  - [Callouts](#callouts)
  - [Lists](#lists)
  - [Task lists](#task-lists)
  - [Code blocks](#code-blocks)
  - [Tables](#tables)
  - [Frontmatter (properties)](#frontmatter-properties)
  - [Images](#images)
- [Inline elements](#inline-elements)
  - [Emphasis](#emphasis)
  - [Strikethrough](#strikethrough)
  - [Highlight](#highlight)
  - [Inline code](#inline-code)
  - [Markdown links](#markdown-links)
  - [Autolinks](#autolinks)
  - [Wikilinks](#wikilinks)
  - [Embeds](#embeds)
  - [Tags](#tags)
  - [Comments](#comments)
  - [Backslash escapes](#backslash-escapes)
  - [Emoji](#emoji)
- [Not supported](#not-supported)

## Rendering at a glance

The reader draws black proportional text on a white background. The accent color is
Obsidian purple (`#7C3AED`). Cheat sheet:

| Element | Font | Look |
|---|---|---|
| Body text | proportional, small | black on white |
| H1 / H2 | large, bold | extra space above |
| H3 / H4 | medium, bold | extra space above |
| H5 / H6 | small, bold | extra space above |
| Inline code, code blocks | monospace | light-gray background `#F2F2F2`; blocks get a border + left padding |
| Web link / autolink | body | blue `#1A4FBF`, underlined |
| Wikilink | body | purple `#7C3AED`, underlined |
| `#tag` | body | purple text on a light-purple `#EFE7FD` rounded pill |
| `==highlight==` | body | yellow `#FFF176` background |
| `~~strike~~` | body | line through the middle |
| Blockquote | body | gray text behind a 3 px lilac `#B39DDB` left bar |
| Callout | body | light-purple `#EFE7FD` box with a bold title |
| Table row | monospace, small | raw text, exactly as typed |
| Frontmatter | small, gray | gray `--- properties ---` box with the raw lines |
| Task checkbox | — | drawn square; filled with a check mark when done |
| Horizontal rule | — | thin gray line |
| Emoji | color glyph, 16x16 px | inline color icon, not text — see [Emoji](#emoji) |

The focused link (D-pad left/right) gets a purple rounded outline on top of all of this.

---

## Block elements

### Headings

ATX headings only: 1–6 `#` characters **followed by a space**. Trailing `#`s are stripped.
Inline formatting (bold, links, tags…) works inside heading text.

```markdown
# H1 — large bold
## H2 — large bold
### H3 — medium bold
#### H4 — medium bold
##### H5 — small bold
###### H6 — small bold
```

On the phone: H1/H2 render in the large bold font, H3/H4 medium bold, H5/H6 in the body
size but bold. Every heading gets extra margin above it so sections visually separate.
`#Word` without the space is not a heading — it's parsed as a [tag](#tags).

Setext headings (a line underlined with `===` or `---`) are **not** supported — see
[Not supported](#not-supported).

### Paragraphs and line breaks

A blank line separates paragraphs. Consecutive non-blank lines join into **one** paragraph,
and the newline between them renders as a space — the reader soft-wraps everything to the
screen width word by word.

```markdown
This line and
this line are one paragraph on the phone.

This is a second paragraph.
```

There is **no hard line break inside a paragraph**: neither two trailing spaces nor a
trailing backslash produce one. If you want a visual break, use a blank line (new
paragraph) or a list.

### Horizontal rules

A line consisting only of three or more `-`, `*` or `_` (spaces between them allowed):

```markdown
---
* * *
___
```

Renders as a thin gray line across the content width. Two gotchas:

- `- something` is a **bullet**, not an HR — list and heading checks win.
- `---` as the *very first line of the file* opens [frontmatter](#frontmatter-properties),
  not an HR.

### Blockquotes

One or more leading `>` per line. Repeated `>` nest:

```markdown
> A quoted line.
> Another quoted line.
>> Nested one level deeper.
```

Renders with a 3 px lilac (`#B39DDB`) vertical bar on the left and gray text. Each extra
`>` level indents the content further. Quotes are line-based: every `>` line is its own
quote line, so there is no "lazy continuation" (a plain line after a quote starts a normal
paragraph).

### Callouts

A quote line whose content starts with `[!type]` becomes an Obsidian-style callout. Any
word works as the type; it is lowercased internally.

```markdown
> [!note] Remember this
> The body of the callout continues
> on further quoted lines.

> [!warning] Battery
> Sync drains the E71 fast on 3G.
```

Renders as a light-purple (`#EFE7FD`) box with the title in **bold**. The quoted lines that
follow form the callout body underneath the title. Differences from desktop Obsidian:

- **All types look the same** — `[!note]`, `[!warning]`, `[!tip]`, `[!anything]` all get the
  same purple box. There are no per-type icons or colors.
- Foldable callouts (`[!note]-` / `[!note]+`) are not folded — the `-`/`+` is just text.
- Give the callout a title; with no title the header line is just empty.

### Lists

**Bullets** start with `- `, `* ` or `+ `. **Numbered** items start with a number followed
by `. ` or `) `. Nesting: every **2 leading spaces or 1 tab** adds one level.

```markdown
- fruit
  - apples
  - pears
    - conference
- vegetables

1. first
2. second
   Note: 3 spaces still rounds to one level on 2-space math — stick to 2 or 4.
4. numbers are shown as typed
1) paren form works too
```

On the phone each level indents further; bullets get a bullet glyph, numbered items show
their label. Two things to know:

- **Numbers are not renumbered.** The label you type is the label you see (a `4.` after a
  `2.` stays `4.`). Both `12.` and `12)` display as `12.`.
- **One line per item.** There is no multi-line item continuation; a plain (unindented,
  non-list) line after an item starts a new paragraph, and an indented plain paragraph
  under an item is not supported.

Inline formatting, links, and tags all work inside list items.

### Task lists

The Obsidian checkbox syntax, on a `- ` bullet:

```markdown
- [ ] flash the E71
- [x] build Noksidian.jar
- [X] capital X counts as done too
  - [ ] tasks nest like bullets
```

The reader draws a real checkbox square per item — filled with a check mark when the task
is done. Checkboxes are **display only**: you can't toggle them from the reader; open the
note in the editor and type the `x` yourself. Only `[ ]`, `[x]` and `[X]` are recognized —
other Obsidian "custom checkboxes" like `[-]` or `[>]` render as a plain bullet whose text
starts with the bracket characters.

### Code blocks

**Fenced**: open with `` ``` `` or `~~~`, optionally followed by a language word, close
with the matching fence (an unclosed fence runs to the end of the note):

````markdown
```java
StringBuffer sb = new StringBuffer();
sb.append("no generics here");
```
````

**Indented**: any line indented by 4+ spaces, outside of a list, is also a code block.

```markdown
    plain indented code
    also works
```

Both render verbatim — no inline parsing, no markdown, no links — in the monospace font on
a light-gray (`#F2F2F2`) box with a border and left padding. The language word is parsed
but there is **no syntax highlighting**; everything in the block is one color. Long lines
are not horizontally scrollable, so keep code narrow if you read it on the phone.

### Tables

A line starting with `|` (or containing ` | ` with at least 2 cells) is a table row. The
separator row (`|---|---|`) is recognized and dropped.

```markdown
| Key       | Value |
| --------- | ----- |
| owner     | miha  |
| branch    | main  |
```

Tables render as **raw monospace rows** — each line is drawn exactly as typed, in the small
monospace font, with the pipes visible. There is no column-width computation, no alignment
(the `:---:` colons do nothing), and cell contents are **not** inline-parsed: `**bold**` in
a cell shows the asterisks, and links in cells are not tappable.

Practical tip: pre-align your pipes with spaces (desktop Obsidian's table editor does this
for you) and keep rows short — the E71 fits only a few dozen monospace characters across.

### Frontmatter (properties)

If the **very first line** of the file is `---`, everything up to the closing `---` is
YAML frontmatter:

```markdown
---
tags: [homelab, phone]
created: 2026-07-01
---
```

The reader shows it as a compact gray `--- properties ---` box containing the raw lines in
small gray text. The YAML is **not parsed** — keys, tags and aliases in frontmatter are
display-only and do not feed tag search or wikilink resolution. A `---` anywhere other than
line 1 is a [horizontal rule](#horizontal-rules).

### Images

A line that is *entirely* an image (trailing spaces allowed) becomes an image block; both
syntaxes work, and both also work [inline](#embeds) inside a paragraph:

```markdown
![vault map](vault-map.png)
![[attachments/vault-map.png]]
```

How the source is resolved and drawn:

- **Local file** — resolved like a wikilink (case-insensitive, by basename anywhere in the
  vault, or by explicit relative path), loaded, and **downscaled to fit the content width**
  (nearest-neighbor — fast, slightly blocky). D-pad-focus an inline image and press the
  center key to open it in the full-screen image viewer with 1:1 zoom and panning.
- **`http(s)://` source** — not downloaded. Shown as a blue link reading `[img: alt]`;
  activating it opens the URL in the phone's web browser.
- **Too big** — files over ~400 KB are never decoded (the E71 heap can't take it); you get
  an "image too large" placeholder box instead.
- **Undecodable** — anything the phone's decoder rejects shows a `[image: name]`
  placeholder box; the note keeps rendering.

Recognized extensions: `png`, `jpg`, `jpeg`, `gif`, `bmp`. What actually decodes depends on
the phone — the E71 reliably handles PNG, JPEG and GIF. Decoded images are cached only while
the current note is open.

---

## Inline elements

### Emphasis

```markdown
*italic* and _italic_
**bold** and __bold__
***bold italic***
**bold with *italic inside* still bold**
```

Styles are a bitmask, so nesting **combines**: in the last example the inner words render
bold *and* italic. Emphasis also stacks with strikethrough, highlight, links and code
backgrounds.

### Strikethrough

```markdown
~~done with this~~
```

Draws a line through the middle of the text.

### Highlight

```markdown
==the important bit==
```

Renders on a yellow (`#FFF176`) background, like a marker pen.

### Inline code

```markdown
Use `Config.get` for settings.
Double backticks let you show a literal backtick: `` `code` ``.
```

Monospace on the light-gray background. Nothing inside the backticks is parsed — `` `*x*` ``
shows the asterisks. The double-backtick form exists exactly so you can put a backtick
*inside* code.

### Markdown links

```markdown
[Noksidian on GitHub](https://github.com/example/noksidian)
[with a title](https://example.com "titles are accepted and ignored")
```

Blue (`#1A4FBF`) and underlined. The link text may itself carry inline styles. Activating a
`http(s)` link (D-pad focus + center key) hands the URL to the phone's web browser — Symbian
may ask for permission. The quoted title after the URL is ignored.

### Autolinks

Bare URLs and angle-bracket URLs both become links, no markup needed:

```markdown
https://example.com/page
<http://192.168.1.50:8180>
```

Only `http://` and `https://` are recognized; a bare URL runs up to the next whitespace.
`mailto:` and other schemes are not autolinked (write them as `[text](mailto:…)` and the
phone will still likely refuse the scheme — treat non-http links as decoration).

### Wikilinks

All four Obsidian forms:

```markdown
[[Home lab]]
[[Home lab|the lab note]]
[[Home lab#Networking]]
[[Home lab#Networking|see networking]]
```

Purple (`#7C3AED`) and underlined. The displayed text is the alias if given, otherwise the
full inner text (so `[[Home lab#Networking]]` displays exactly that). Resolution — same
rules as desktop Obsidian's shortest-path mode:

- case-insensitive; matches the exact vault-relative path, the path + `.md`, or the
  **basename** anywhere in the vault (`[[Home lab]]` finds `Projects/Home lab.md`);
- several notes with the same basename → the shortest path wins;
- a `#Heading` part opens the right note but does **not** scroll to the heading — you land
  at the top;
- an unresolvable target offers to **create** the note (Yes/No prompt), exactly like
  Obsidian.

### Embeds

`![[…]]` is supported **for images only**:

```markdown
![[vault-map.png]]        <- renders the image (block or inline)
![[Some other note]]      <- does NOT transclude; shows a "[image: Some other note]" placeholder box
```

Note transclusion — embedding another note's *content* — is not supported on the phone.
An embedded `.md` file is treated as an image that fails to decode, so you get a harmless
placeholder box; the file itself is untouched and still renders fine in desktop Obsidian.
Use a plain `[[wikilink]]` instead and jump to the note. The Obsidian `![[img.png|300]]`
width modifier is also unsupported — images always scale to fit the screen width (see
[Not supported](#not-supported)).

### Tags

```markdown
#homelab #project/noksidian #e71_notes #2026-07
```

A `#` at the start of a line, or after whitespace/punctuation, starts a tag. Valid tag
characters: **letters, digits, `-`, `_` and `/`** (so nested tags like `#project/noksidian`
work). A tag must contain **at least one non-digit**: `#2026` is plain text, `#2026-07` and
`#v2` are tags.

Tags render as purple text on a light-purple rounded **pill**, `#` included. Activating a
tag runs a vault-wide search for it. Mid-word hashes (`foo#bar`) and headings (`# Title`,
note the space) are not tags. Frontmatter `tags:` lines are not parsed — only inline `#tags`
count.

### Comments

Obsidian comments are hidden in the reader (but still saved in the file and synced):

```markdown
Visible %%invisible inline comment%% visible again.

%%
Whole lines between block markers
are skipped entirely.
%%
```

### Backslash escapes

A backslash before a punctuation character emits that character literally, suppressing its
markdown meaning:

```markdown
\*not italic\*  \[\[not a wikilink\]\]  \#nota-tag  \`not code\`  \==plain==
```

Before letters, digits or whitespace the backslash has no special meaning and simply shows
up as text.

### Emoji

Real Unicode emoji render as small color glyphs instead of the empty boxes the phone's own
fonts would otherwise draw. Coverage is the full RGI (Recommended for General Interchange)
emoji set — not a curated subset — including flags, skin-tone modifiers, keycaps (a digit,
`#` or `*` combined with the keycap marker), and ZWJ sequences (families, professions,
multi-person groups, and the like) that have a dedicated composite glyph.

A ZWJ sequence with no composite glyph in the pack falls back to its component emoji, drawn
one after another — the behavior Unicode itself recommends for an unsupported combination.
Anything the pack still can't place (an unrecognized symbol, a lone joiner with nothing to
fall back to) is dropped silently, the same as plain text always did before this feature
existed.

Glyphs are 16x16 px and bundled inside the app — nothing is downloaded — and they appear
wherever inline text flows: paragraphs, headings, quotes, callouts, list items, and inline
code. At a larger [Font size](user-guide.md#look-and-feel) they upscale nearest-neighbor
along with the surrounding text, same as everything else at that zoom.

Three places still strip emoji to nothing, unchanged from before this feature: fenced
[code blocks](#code-blocks) (raw text, never rendered), [table](#tables) cells, and
list/library chrome — the Library's file list, dialog titles, and similar UI text outside
the rendered note itself.

---

## Not supported

Being explicit about the gaps is half the point of this page. None of these break the
reader — every one degrades to something harmless — and your files stay 100% valid for
desktop Obsidian.

| Construct | What happens on the phone |
|---|---|
| **Mermaid diagrams** | The ` ```mermaid ` fence renders as an ordinary gray code block showing the diagram source. |
| **LaTeX / MathJax** (`$x^2$`, `$$…$$`) | Rendered as literal text, dollar signs and all. |
| **Footnotes** (`[^1]` and `[^1]: …`) | Rendered literally: the marker shows as plain text, the definition line as a normal paragraph. No superscripts, no jumping. |
| **Dataview, Templater, any plugin syntax** | Not executed. Inline queries render as literal text; ` ```dataview ` fences render as code blocks showing the query. |
| **Setext headings** (`Title` underlined with `===` or `---`) | The title renders as a plain paragraph. A `---` underline then renders as a horizontal rule; an `===` underline joins the paragraph as literal text. Use `#` headings. |
| **Note transclusion** (`![[Other note]]`) | Placeholder box, not the note's content — see [Embeds](#embeds). |
| **Block references** (`[[Note^block]]`, `^block-id`) | The `^…` is treated as part of the link target, so the link won't resolve (and offers to create a bogus note — say No). `^block-id` anchors render as literal text. |
| **Image width modifier** (`![[img.png\|300]]`) | Not understood and may break image resolution (placeholder box). Images always scale to fit the screen width. |
| **Hard line breaks** (two trailing spaces, trailing `\`) | Ignored; a newline inside a paragraph renders as a space. Use a blank line. |
| **Raw HTML** (`<br>`, `<img>`, `<div>`…) | Rendered as literal text, angle brackets included (except `<http://…>` [autolinks](#autolinks)). |
| **Reference-style links** (`[text][ref]` + `[ref]: url`) | Rendered literally. Use inline `[text](url)`. |
| **Syntax highlighting** in code blocks | The language word after the fence is accepted but everything renders in one color. |
| **Table alignment / rich cells** | Separator colons ignored; rows drawn as raw monospace text; no formatting or links inside cells. |
| **Heading jumps** (`[[Note#Heading]]`) | Opens the note at the top; the heading part only picks the note. |
| **Auto-renumbering** of ordered lists | Numbers display exactly as typed. |
| **Custom checkboxes** (`[-]`, `[>]`, `[?]`…) | Only `[ ]`/`[x]`/`[X]` draw a checkbox; others render as a plain bullet. |
| **Frontmatter parsing** | Shown raw in the gray properties box; `tags:`/`aliases:` there have no effect on search or link resolution. |
| **Emoji shortcodes** (`:smile:`) | Rendered as literal text — shortcodes are never expanded. Real Unicode emoji render as color glyphs instead; see [Emoji](#emoji) for exactly what's covered and where it doesn't apply. |
| **Foldable callouts / per-type callout styling** | All callouts get the same purple box; `+`/`-` fold markers render as text. |

If a note leans hard on any of the above, it still opens fine — you just read the source
form of that construct instead of a rendering.
