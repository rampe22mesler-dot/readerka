package org.readerka.desktop;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import javax.swing.*;
import kotlinx.serialization.json.Json;
import org.junit.Test;
import org.readerka.data.*;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Direct Swing actions use a temporary library and never capture native input. */
public class EditorDraftUiTest {
    private DesktopWindow app;
    private EntryPanel entry;
    private void edt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private Timer timer(EntryPanel panel, String name) {
        try {
            Field field = EntryPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Timer) field.get(panel);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void insert(String value) {
        try { entry.getEditor().getTextPane().getDocument().insertString(0, value, null); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    @Test public void draftsQuotesUndoAndIdleTimersSurviveOptimization() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        File directory = Files.createTempDirectory("readerka-draft-ui").toFile();
        try (DesktopRepository repo = new DesktopRepository(directory)) {
            LibrarySnapshot data = Json.Default.decodeFromString(LibrarySnapshot.Companion.serializer(),
                "{\"entries\":[{\"id\":\"note\",\"title\":\"Note\",\"comment\":\"# Heading\\n\\noriginal\"},{\"id\":\"quote\",\"title\":\"Quote\",\"kind\":\"Quote\",\"quote\":\"Исходная цитата\",\"comment\":\"comment\"},{\"id\":\"consp\",\"title\":\"Consp\",\"kind\":\"Consp\",\"comment\":\"text\"}]}");
            repo.persist(data, null, null);
            LibrarySession session = new LibrarySession(repo);
            try {
                edt(() -> {
                    app = new DesktopWindow(session);
                    app.openEntry("note", false, null);
                    entry = app.getEditors().get("note");
                    assertFalse(timer(entry, "timer").isRunning());
                    assertFalse(timer(entry, "autosave").isRunning());
                    insert("Новый ");
                    assertTrue(timer(entry, "timer").isRunning());
                    entry.getEditor().getTextPane().select(2, 5);
                    entry.flush();
                    assertFalse(timer(entry, "timer").isRunning());
                    EntryDraft draft = repo.workspace().getDrafts().get(0);
                    assertEquals(entry.getEditor().getRich().markdown(), draft.getEntry().getComment());
                    assertEquals(2, draft.getCursorStart());
                    assertEquals(5, draft.getCursorEnd());
                    assertEquals("2", repo.preference("cursor-start:note"));
                    String markdown = draft.getEntry().getComment();
                    entry.getEditor().getTextPane().setCaretPosition(1);
                    entry.flush();
                    assertEquals(markdown, session.draft("note").getEntry().getComment());
                    entry.save();
                    String saved = entry.getEditor().getRich().getText();
                    insert("undo ");
                    entry.undo();
                    assertEquals(saved, entry.getEditor().getRich().getText());
                    entry.redo();
                    assertTrue(entry.getEditor().getRich().getText().startsWith("undo "));
                    entry.flush();
                    app.openEntry("quote", false, null);
                    entry = app.getEditors().get("quote");
                    entry.getQuotation().setText("Изменённая цитата");
                    insert("Новый комментарий ");
                    entry.save();
                    assertEquals("Изменённая цитата", session.draft("quote").getEntry().getQuote());
                    assertTrue(session.draft("quote").getEntry().getComment().contains("Новый комментарий"));
                    app.openEntry("consp", false, null);
                    assertTrue(timer(app.getEditors().get("consp"), "autosave").isRunning());
                });
                edt(() -> {});
            } finally {
                edt(() -> {
                    if (app != null) { app.getEditors().values().forEach(EntryPanel::disposePanel); app.dispose(); }
                });
            }
        }
        try (DesktopRepository reopened = new DesktopRepository(directory)) {
            EntryDraft draft = reopened.workspace().getDrafts().stream().filter(d -> d.getEntry().getId().equals("note")).findFirst().orElseThrow();
            assertTrue(draft.getEntry().getComment().contains("undo Новый"));
            assertFalse(reopened.snapshot().getNoteVersions().isEmpty());
            assertEquals("Изменённая цитата", reopened.snapshot().getEntries().stream().filter(e -> e.getId().equals("quote")).findFirst().orElseThrow().getQuote());
        } finally {
            try (var paths = Files.walk(directory.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
