package com.learne.backend.teacher.content;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import com.learne.backend.teacher.content.importexport.security.TcpClamAvClient;
import com.learne.backend.teacher.content.importexport.archive.CourseImportArchiveReader;
import com.learne.backend.teacher.content.importexport.archive.CourseImportPackage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CourseImportSecurityTests {
    @Autowired
    private CourseImportArchiveReader zipReader;

    @Autowired
    private AudioFileValidator audioFileValidator;

    @Test
    void zipReaderRejectsZipSlipPath() throws Exception {
        byte[] zip = zipWithEntry("../evil.txt", "bad".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> zipReader.read(zip, "course.zip"))
                .hasMessageContaining("Đường dẫn trong gói nhập không hợp lệ");
    }

    @Test
    void zipReaderRejectsRootAbsolutePath() throws Exception {
        byte[] zip = zipWithEntry("/evil.xlsx", "bad".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> zipReader.read(zip, "course.zip"))
                .hasMessageContaining("Đường dẫn trong gói nhập không hợp lệ");
    }

    @Test
    void zipReaderRejectsDriveLetterAbsolutePath() throws Exception {
        byte[] zip = zipWithEntry("C:/evil.xlsx", "bad".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> zipReader.read(zip, "course.zip"))
                .hasMessageContaining("Đường dẫn trong gói nhập không hợp lệ");
    }

    @Test
    void zipReaderRejectsTraversalSegment() throws Exception {
        byte[] zip = zipWithEntry("audio/../evil.xlsx", "bad".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> zipReader.read(zip, "course.zip"))
                .hasMessageContaining("Đường dẫn trong gói nhập không hợp lệ");
    }

    @Test
    void zipReaderCountsDirectoryEntriesTowardEntryLimit() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int i = 0; i < 300; i++) {
                zip.putNextEntry(new ZipEntry("folder-%03d/".formatted(i)));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("course.xlsx"));
            zip.write("workbook".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> zipReader.read(output.toByteArray(), "course.zip"))
                .hasMessageContaining("Gói nhập vượt quá số lượng file cho phép");
    }

    @Test
    void audioValidatorRejectsRenamedTextFile() {
        CourseImportPackage.ImportFile file = new CourseImportPackage.ImportFile(
                "audio/fake.mp3",
                "audio/mpeg",
                "not really mp3".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> audioFileValidator.validate(file))
                .hasMessageContaining("File audio không hợp lệ");
    }

    @Test
    void clamAvClientRejectsUnexpectedResponse() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> server = respondOnce(serverSocket, "stream: UNKNOWN\n");
            TcpClamAvClient client = new TcpClamAvClient("localhost", serverSocket.getLocalPort());

            assertThatThrownBy(() -> client.assertClean("file.mp3", "audio".getBytes(StandardCharsets.UTF_8)))
                    .hasMessageContaining("Không thể quét ClamAV lúc này");

            server.join();
        }
    }

    @Test
    void clamAvClientAcceptsExactCleanResponseAndUsesLineProtocol() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> server = assertCommandAndRespond(serverSocket, "nINSTREAM\n", "stream: OK\n");
            TcpClamAvClient client = new TcpClamAvClient("localhost", serverSocket.getLocalPort());

            assertThatCode(() -> client.assertClean("file.mp3", "audio".getBytes(StandardCharsets.UTF_8)))
                    .doesNotThrowAnyException();

            server.join();
        }
    }

    @Test
    void clamAvClientRejectsNonExactOkResponse() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            CompletableFuture<Void> server = respondOnce(serverSocket, "stream: NOTOK\n");
            TcpClamAvClient client = new TcpClamAvClient("localhost", serverSocket.getLocalPort());

            assertThatThrownBy(() -> client.assertClean("file.mp3", "audio".getBytes(StandardCharsets.UTF_8)))
                    .hasMessageContaining("Không thể quét ClamAV lúc này");

            server.join();
        }
    }

    private byte[] zipWithEntry(String name, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private CompletableFuture<Void> respondOnce(ServerSocket serverSocket, String response) {
        return CompletableFuture.runAsync(() -> {
            try (Socket socket = serverSocket.accept()) {
                socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private CompletableFuture<Void> assertCommandAndRespond(ServerSocket serverSocket, String command, String response) {
        return CompletableFuture.runAsync(() -> {
            try (Socket socket = serverSocket.accept()) {
                InputStream input = socket.getInputStream();
                byte[] commandBytes = input.readNBytes(command.length());
                assertThat(new String(commandBytes, StandardCharsets.US_ASCII)).isEqualTo(command);
                drainInstreamPayload(input);
                socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void drainInstreamPayload(InputStream input) throws IOException {
        DataInputStream dataInput = new DataInputStream(input);
        int length;
        while ((length = dataInput.readInt()) > 0) {
            dataInput.readNBytes(length);
        }
    }
}
