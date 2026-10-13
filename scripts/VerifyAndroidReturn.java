import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import kotlinx.serialization.json.Json;
import org.readerka.data.*;
import org.readerka.export.*;
import org.readerka.desktop.DesktopPdfEngine;

/** Run against the existing, unchanged desktop libraries, outside the desktop source tree. */
class VerifyAndroidReturn {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static Entry entry(LibrarySnapshot data, String id) {
        return data.getEntries().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }
    static byte[] hash(Path path) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int size;
            while ((size = input.read(buffer)) >= 0) md.update(buffer, 0, size);
        }
        return md.digest();
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 3, "Usage: VerifyAndroidReturn.java return.zip expected.json original.zip");
        Path root = Files.createTempDirectory(Path.of("/tmp"), "readerka-desktop-return-");
        try {
            StagedBackup returned;
            try (var input = new FileInputStream(args[0])) {
                returned = BackupArchive.INSTANCE.stage(input, root.resolve("returned").toFile());
            }
            StagedBackup original;
            try (var input = new FileInputStream(args[2])) {
                original = BackupArchive.INSTANCE.stage(input, root.resolve("original").toFile());
            }
            var expected = Json.Default.decodeFromString(LibrarySnapshot.Companion.serializer(), Files.readString(Path.of(args[1])));
            var data = returned.getData();
            check(data.getVersion() == 5, "ZIP 5");
            for (var method : LibrarySnapshot.class.getMethods()) {
                if (!method.getName().startsWith("get") || method.getName().equals("getEntries") || method.getParameterCount() != 0) continue;
                Object before = method.invoke(expected), after = method.invoke(data);
                if (before instanceof List<?> a && after instanceof List<?> b)
                    check(a.size() == b.size() && new HashSet<>(a).equals(new HashSet<>(b)), method.getName() + " lost data");
                else check(Objects.equals(before, after), method.getName() + " changed");
            }
            for (var old : expected.getEntries()) {
                var next = entry(data, old.getId());
                if (!old.getId().equals("interop-consp")) check(old.equals(next), "Canonical entry changed: " + old.getId());
                else {
                    check(next.getTitle().equals("Изменено на Android"), "Android title");
                    check(next.getContent().getText().endsWith("Android: русский текст 🙂"), "Unicode edit");
                    check(next.getContent().getBlocks().subList(0, old.getContent().getBlocks().size()).stream().map(TextBlock::getId).toList()
                        .equals(old.getContent().getBlocks().stream().map(TextBlock::getId).toList()), "Stable block IDs");
                    check(!next.getContent().getMarks().isEmpty(), "RichText marks");
                    check(Objects.equals(old.getSources(), next.getSources()), "Consp source IDs");
                }
            }
            check(data.getEntries().size() == expected.getEntries().size() + 1, "No duplicate entries");
            var quote = entry(data, "android-quote");
            var document = data.getDocuments().get(0);
            Path pdf = returned.getDirectory().toPath().resolve("pdfs").resolve(document.getStoredName());
            check(Arrays.equals(hash(pdf), hash(original.getDirectory().toPath().resolve("pdfs").resolve(document.getStoredName()))), "Source PDF hash");
            try (var engine = new DesktopPdfEngine(pdf.toFile(), root.toFile())) {
                var text = engine.text(quote.getAnchor().getPage());
                var selection = text.selection(quote.getAnchor().getStart(), quote.getAnchor().getEnd());
                check(selection != null && selection.getFirst().equals(quote.getQuote()), "Desktop reopens Android quote at same glyphs");
                var a = quote.getAnchor().getRects(); var b = selection.getSecond().getRects();
                check(a.size() == b.size(), "Rectangle count");
                for (int i = 0; i < a.size(); i++) {
                    check(Math.abs(a.get(i).getLeft() - b.get(i).getLeft()) < .001 && Math.abs(a.get(i).getTop() - b.get(i).getTop()) < .001
                        && Math.abs(a.get(i).getRight() - b.get(i).getRight()) < .001 && Math.abs(a.get(i).getBottom() - b.get(i).getBottom()) < .001,
                        "Android/Desktop geometry differs at rectangle " + i);
                }
                var image = engine.render(quote.getAnchor().getPage(), 1200, null);
                int ink = 0;
                for (var rect : a) {
                    boolean found = false;
                    for (int y = Math.max(0, (int)(rect.getTop() * image.getHeight()) - 2); y < Math.min(image.getHeight(), (int)(rect.getBottom() * image.getHeight()) + 3); y++)
                        for (int x = Math.max(0, (int)(rect.getLeft() * image.getWidth()) - 2); x < Math.min(image.getWidth(), (int)(rect.getRight() * image.getWidth()) + 3); x++)
                            if ((image.getRGB(x, y) & 0xffffff) < 0xcccccc) found = true;
                    if (found) ink++;
                }
                check(ink >= a.size() * .8, "Desktop rendered ink does not overlap Android anchor rectangles: " + ink + "/" + a.size());
                var graphics = image.createGraphics();
                graphics.setColor(new java.awt.Color(255, 190, 0, 100));
                for (var rect : a) graphics.fill(new java.awt.geom.Rectangle2D.Float(rect.getLeft() * image.getWidth(), rect.getTop() * image.getHeight(),
                    (rect.getRight() - rect.getLeft()) * image.getWidth(), (rect.getBottom() - rect.getTop()) * image.getHeight()));
                graphics.dispose();
                javax.imageio.ImageIO.write(image, "png", Path.of(args[0]).toAbsolutePath().getParent().resolve("desktop-return-quote.png").toFile());
            }
            System.out.println("PASS: unchanged desktop opens Android ZIP 5; all categories, field geometry, identities, marks, block IDs, PDF SHA-256 and rotated quote geometry preserved.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
