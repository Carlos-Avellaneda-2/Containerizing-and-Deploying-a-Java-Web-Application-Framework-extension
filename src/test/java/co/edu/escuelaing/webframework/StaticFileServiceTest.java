package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticFileServiceTest {

    private final StaticFileService classpath = StaticFileService.fromClasspath("/webroot");

    @Test
    void servesHtmlWithTheRightContentType() throws Exception {
        Response response = classpath.serve("/index.html").orElseThrow();

        assertEquals(200, response.getStatus());
        assertEquals("text/html; charset=UTF-8", response.getContentType());
        assertTrue(response.getBodyAsString().contains("<html"));
    }

    @Test
    void servesCssAndJavaScript() throws Exception {
        assertEquals("text/css; charset=UTF-8", classpath.serve("/styles.css").orElseThrow().getContentType());
        assertEquals("text/javascript; charset=UTF-8", classpath.serve("/app.js").orElseThrow().getContentType());
    }

    @Test
    void servesBinaryImagesByteForByte() throws Exception {
        Response response = classpath.serve("/images/logo.png").orElseThrow();

        byte[] expected;
        try (InputStream in = StaticFileServiceTest.class.getResourceAsStream("/webroot/images/logo.png")) {
            expected = in.readAllBytes();
        }
        assertEquals("image/png", response.getContentType());
        assertArrayEquals(expected, response.getBody());
        assertEquals((byte) 0x89, response.getBody()[0]); // PNG signature: 89 50 4E 47
        assertEquals('P', response.getBody()[1]);
    }

    @Test
    void rootPathServesIndexHtml() throws Exception {
        Response root = classpath.serve("/").orElseThrow();

        assertArrayEquals(classpath.serve("/index.html").orElseThrow().getBody(), root.getBody());
    }

    @Test
    void missingResourceIsEmpty() throws Exception {
        assertTrue(classpath.serve("/nope.html").isEmpty());
        assertTrue(classpath.serve("/images/nope.png").isEmpty());
    }

    @Test
    void aFolderIsNotServedAsAFile() throws Exception {
        assertTrue(classpath.serve("/images").isEmpty());
        assertTrue(classpath.serve("/images/").isEmpty());
    }

    @Test
    void directoryTraversalIsRefused() throws Exception {
        assertTrue(classpath.serve("/../pom.xml").isEmpty());
        assertTrue(classpath.serve("/images/../index.html").isEmpty());
        assertTrue(classpath.serve("/..\\index.html").isEmpty());
        assertFalse(StaticFileService.isSafe("/a/../b"));
        assertFalse(StaticFileService.isSafe("/a\0b"));
        assertTrue(StaticFileService.isSafe("/a/b.txt"));
    }

    @Test
    void unknownExtensionsAreOctetStream() {
        assertEquals("application/octet-stream", StaticFileService.contentTypeFor("/data.bin"));
        assertEquals("application/octet-stream", StaticFileService.contentTypeFor("/README"));
        assertEquals("image/jpeg", StaticFileService.contentTypeFor("/PHOTO.JPG"));
    }

    @Test
    void servesFilesFromADirectoryOnDisk(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("hello.txt"), "from disk", StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("sub").resolve("index.html"), "<h1>sub</h1>");
        StaticFileService service = StaticFileService.fromDirectory(dir);

        assertEquals("from disk", service.serve("/hello.txt").orElseThrow().getBodyAsString());
        assertEquals("<h1>sub</h1>", service.serve("/sub/").orElseThrow().getBodyAsString());
        assertTrue(service.serve("/missing.txt").isEmpty());
        assertTrue(service.serve("/sub").isEmpty()); // a folder, not a file
    }

    @Test
    void directoryServiceCannotEscapeItsRoot(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("public"));
        Files.writeString(dir.resolve("secret.txt"), "top secret");
        StaticFileService service = StaticFileService.fromDirectory(root);

        assertTrue(service.serve("/../secret.txt").isEmpty());
    }
}
