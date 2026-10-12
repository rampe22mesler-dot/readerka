package org.readerka.desktop;

import com.formdev.flatlaf.FlatLightLaf;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;
import kotlin.Unit;
import org.junit.Test;
import org.readerka.data.*;
import static org.junit.Assert.*;

public class EditorPerformanceTest {
    private RichEditor editor;
    private void edt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private RichEditor create(RichText value) {
        return new RichEditor(value, (r, a, b) -> Unit.INSTANCE, link -> Unit.INSTANCE, true);
    }
    private void insert(int at, String text) {
        try { editor.getTextPane().getDocument().insertString(at, text, null); }
        catch (BadLocationException e) { throw new AssertionError(e); }
    }
    private void remove(int at, int length) {
        try { editor.getTextPane().getDocument().remove(at, length); }
        catch (BadLocationException e) { throw new AssertionError(e); }
    }
    private void compareFullRender() throws Exception {
        edt(() -> {
            RichEditor reference = create(editor.getRich());
            StyledDocument actual = editor.getTextPane().getStyledDocument();
            StyledDocument expected = reference.getTextPane().getStyledDocument();
            assertEquals(editor.getRich().getText(), editor.getTextPane().getText());
            Object[] keys = { StyleConstants.Bold, StyleConstants.Italic, StyleConstants.Underline,
                StyleConstants.FontFamily, StyleConstants.FontSize, StyleConstants.Foreground,
                StyleConstants.Background, StyleConstants.LeftIndent, StyleConstants.FirstLineIndent,
                StyleConstants.SpaceAbove, StyleConstants.SpaceBelow };
            for (int at = 0; at <= actual.getLength(); at++) {
                for (Object key : keys) {
                    boolean paragraph = key == StyleConstants.LeftIndent || key == StyleConstants.FirstLineIndent
                        || key == StyleConstants.SpaceAbove || key == StyleConstants.SpaceBelow;
                    AttributeSet a = (paragraph ? actual.getParagraphElement(at) : actual.getCharacterElement(at)).getAttributes();
                    AttributeSet b = (paragraph ? expected.getParagraphElement(at) : expected.getCharacterElement(at)).getAttributes();
                    assertEquals("offset=" + at + " attribute=" + key, b.getAttribute(key), a.getAttribute(key));
                }
            }
        });
    }
    @Test public void incrementalFormattingMatchesFullRenderAcrossEditsAndUndo() throws Exception {
        edt(() -> {
            FlatLightLaf.setup();
            editor = create(RichText.Companion.fromMarkdown("# Heading\n\n**bold** and [link](https://example.test)\n\n- list\n- next\n\n> quote\n\n    code\n\nlast"));
        });
        Random random = new Random(6147);
        for (int i = 0; i < 65; i++) {
            final int step = i;
            edt(() -> {
                int length = editor.getRich().getText().length();
                int at = random.nextInt(length + 1);
                if (step % 7 == 0) {
                    insert(at, "\nЖ\n");
                    insert(0, "prefix");
                    remove(0, 3);
                } else if (step % 5 == 0 && at < length) {
                    remove(at, Math.min(length - at, 1 + random.nextInt(8)));
                } else insert(at, "x");
            });
            compareFullRender();
        }
        edt(() -> { editor.undo(); editor.redo(); });
        compareFullRender();
        edt(() -> {
            insert(0, "pending\n");
            editor.replace(RichText.Companion.fromMarkdown("## replacement\n\nordinary"));
            insert(editor.getRich().getText().length(), " new");
        });
        compareFullRender();
    }
    @Test public void burstTypingDoesNotReformatUnchangedParagraphs() throws Exception {
        AtomicLong attributed = new AtomicLong();
        edt(() -> {
            editor = create(RichText.Companion.plain("long ordinary paragraph\n".repeat(500)));
            editor.getTextPane().getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) {}
                public void removeUpdate(DocumentEvent e) {}
                public void changedUpdate(DocumentEvent e) { attributed.addAndGet(e.getLength()); }
            });
            for (int i = 0; i < 80; i++) insert(15, "x");
        });
        edt(() -> {});
        assertTrue("Attribute updates must stay within the edited paragraph: " + attributed.get(), attributed.get() < 1000);
        edt(() -> {
            assertEquals(500 * "long ordinary paragraph\n".length() + 80, editor.getRich().getText().length());
        });
    }
    @Test public void markdownRemainsByteIdenticalToPreviousExporter() {
        Random random = new Random(1945);
        for (int round = 0; round < 120; round++) {
            RichText plain = RichText.Companion.plain("Заголовок\n**a_b** [x] `y` \\ Ж\nnumber\nnext\nquote\ncode\nlink\nend\n");
            List<TextBlock> blocks = new ArrayList<>();
            for (int i = 0; i < plain.getBlocks().size(); i++) {
                TextBlock b = plain.getBlocks().get(i);
                blocks.add(new TextBlock(b.getStart(), b.getId(), BlockStyle.values()[random.nextInt(BlockStyle.values().length)],
                    1 + random.nextInt(3), "readerka://entry/source"));
            }
            List<TextMark> marks = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                int start = random.nextInt(plain.getText().length());
                int end = start + 1 + random.nextInt(plain.getText().length() - start);
                marks.add(new TextMark(start, end, TextStyle.values()[random.nextInt(TextStyle.values().length)], "target" + i % 3));
            }
            RichText value = new RichText(plain.getText(), marks, blocks);
            assertEquals("round=" + round, legacyMarkdown(value), value.markdown());
        }
    }
    private String legacyMarkdown(RichText value) {
        List<String> paragraphs = new ArrayList<>();
        int ordered = 0;
        for (TextBlock b : value.getBlocks()) {
            int end = value.blockEnd(b);
            String content = b.getStyle() == BlockStyle.Code ? value.getText().substring(b.getStart(), end) : legacyInline(value, b.getStart(), end);
            String anchor = "<a id=\"" + b.getId() + "\"></a>\n\n";
            ordered = b.getStyle() == BlockStyle.Ordered ? ordered + 1 : 0;
            switch (b.getStyle()) {
                case Heading: content = "#".repeat(b.getLevel()) + " " + content; break;
                case Quote: content = "> " + content; break;
                case Bullet: content = "- " + content; break;
                case Ordered: content = ordered + ". " + content; break;
                case Code: content = "    " + content; break;
                case Divider: content = "---"; break;
                case LinkCard: content = "[" + RichText.Companion.escapeLabel(value.getText().substring(b.getStart(), end)) + "](" + b.getTarget() + ")"; break;
                default: break;
            }
            paragraphs.add(anchor + content);
        }
        return String.join("\n\n", paragraphs);
    }
    private String legacyInline(RichText value, int start, int end) {
        StringBuilder result = new StringBuilder();
        List<TextMark> active = Collections.emptyList();
        for (int at = start; at <= end; at++) {
            List<TextMark> wanted = new ArrayList<>();
            if (at != end) for (TextMark mark : value.getMarks()) if (at >= mark.getStart() && at < mark.getEnd()) wanted.add(mark);
            wanted.sort(Comparator.comparing((TextMark m) -> m.getStyle().ordinal()).thenComparing(TextMark::getValue));
            if (!wanted.equals(active)) {
                for (int i = active.size() - 1; i >= 0; i--) result.append(marker(active.get(i), false));
                for (TextMark mark : wanted) result.append(marker(mark, true));
                active = wanted;
            }
            if (at < end) {
                char c = value.getText().charAt(at);
                if ("\\*_[]`".indexOf(c) >= 0 && active.stream().noneMatch(m -> m.getStyle() == TextStyle.Code || m.getStyle() == TextStyle.Literal)) result.append('\\');
                result.append(c);
            }
        }
        return result.toString();
    }
    private String marker(TextMark m, boolean open) {
        switch (m.getStyle()) {
            case Bold: return "**";
            case Italic: return "*";
            case Underline: return open ? "<u>" : "</u>";
            case Code: return "`";
            case Link: return open ? "[" : "](" + m.getValue() + ")";
            default: return "";
        }
    }
}
