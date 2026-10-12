package org.readerka.desktop;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.Map;
import kotlinx.serialization.json.Json;
import org.junit.Test;
import org.readerka.data.WorkspaceState;
import static org.junit.Assert.*;

public class EditorPersistenceTest {
    @Test public void draftAndCursorCommitTogetherAndRollbackOnFailure() throws Exception {
        File directory = Files.createTempDirectory("readerka-editor-transaction").toFile();
        WorkspaceState next = Json.Default.decodeFromString(WorkspaceState.Companion.serializer(),
            "{\"drafts\":[{\"entry\":{\"id\":\"note\",\"title\":\"Synthetic\",\"comment\":\"Черновик **текст**\"},\"cursorStart\":3,\"cursorEnd\":7,\"focusedField\":\"body\"}],\"activeDraftId\":\"note\"}");
        Map<String, String> cursor = Map.of("cursor-field:note", "body", "cursor-start:note", "3", "cursor-end:note", "7");
        try (DesktopRepository repo = new DesktopRepository(directory)) {
            WorkspaceState before = repo.workspace();
            Field field = DesktopRepository.class.getDeclaredField("db");
            field.setAccessible(true);
            Connection db = (Connection) field.get(repo);
            db.createStatement().execute("CREATE TRIGGER reject_cursor BEFORE INSERT ON state WHEN NEW.key='preference:cursor-end:note' BEGIN SELECT RAISE(ABORT,'forced write failure'); END");
            try {
                repo.persistWorkspace(next, cursor);
                fail("A failed cursor write must fail the transaction");
            } catch (Exception expected) {
                assertTrue(expected instanceof java.sql.SQLException);
                assertTrue(expected.getMessage().contains("forced write failure"));
            }
            assertEquals(before, repo.workspace());
            assertNull(repo.preference("cursor-start:note"));
            assertNull(repo.preference("cursor-field:note"));
            assertTrue(db.getAutoCommit());
            db.createStatement().execute("DROP TRIGGER reject_cursor");
            repo.persistWorkspace(next, cursor);
            assertEquals(next, repo.workspace());
            repo.persistPreferences(cursor);
        }
        try (DesktopRepository reopened = new DesktopRepository(directory)) {
            assertEquals(next, reopened.workspace());
            for (Map.Entry<String, String> item : cursor.entrySet()) assertEquals(item.getValue(), reopened.preference(item.getKey()));
        } finally {
            try (var paths = Files.walk(directory.toPath())) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
