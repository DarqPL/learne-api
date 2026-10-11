package com.learne.backend.teacher.content.importexport.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.jupiter.api.Test;

class CourseImportArchiveReaderTests {
    private static final int MAX_WORKBOOK_BYTES = 20 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;

    private final CourseImportArchiveReader reader = new CourseImportArchiveReader();

    @Test
    void acceptsZipWithExactlyOneXlsmWorkbookAndAudioFolder() throws Exception {
        byte[] zip = zipOf(
                entry("course-template.xlsm", new byte[] {1, 2, 3}),
                entry("audio/", new byte[0]),
                entry("audio/morning.mp3", new byte[] {4, 5, 6}));

        CourseImportPackage result = reader.read(zip, "course-package.zip");

        assertThat(result.workbook()).containsExactly(1, 2, 3);
        assertThat(result.audioFiles()).containsOnlyKeys("audio/morning.mp3");
        assertThat(result.audioFiles().get("audio/morning.mp3").contentType()).isEqualTo("audio/mpeg");
    }

    @Test
    void acceptsSevenZWithExactlyOneXlsmWorkbookAndAudioFolder() throws Exception {
        byte[] sevenZ = sevenZOf(
                entry("course-template.xlsm", new byte[] {1, 2, 3}),
                entry("audio/morning.mp3", new byte[] {4, 5, 6}));

        CourseImportPackage result = reader.read(sevenZ, "course.7z");

        assertThat(result.workbook()).containsExactly(1, 2, 3);
        assertThat(result.audioFiles()).containsOnlyKeys("audio/morning.mp3");
    }

    @Test
    void acceptsRarWithExactlyOneXlsmWorkbookAndAudioFolder() throws Exception {
        byte[] rar = resourceBytes("/import-archives/course-import.rar");

        CourseImportPackage result = reader.read(rar, "course.rar");

        assertThat(result.workbook()).containsExactly(1, 2, 3);
        assertThat(result.audioFiles()).containsOnlyKeys("audio/morning.mp3");
    }

    @Test
    void acceptsRarFiveCourseTemplatePackage() throws Exception {
        byte[] rar = resourceBytes("/import-archives/course-template-rar5.rar");

        CourseImportPackage result = reader.read(rar, "course-template.rar");

        assertThat(result.workbook()).isNotEmpty();
        assertThat(result.audioFiles()).isEmpty();
    }

    @Test
    void rejectsOversizedZipWorkbook() throws Exception {
        byte[] zip = zipOf(entry("course-template.xlsm", new byte[MAX_WORKBOOK_BYTES + 1]));

        assertThatThrownBy(() -> reader.read(zip, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("File template vượt quá dung lượng cho phép");
    }

    @Test
    void rejectsOversizedZipAudio() throws Exception {
        byte[] zip = zipOf(
                entry("course-template.xlsm", new byte[] {1}),
                entry("audio/morning.mp3", new byte[MAX_AUDIO_BYTES + 1]));

        assertThatThrownBy(() -> reader.read(zip, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("File audio vượt quá dung lượng cho phép");
    }

    @Test
    void rejectsOversizedSevenZWorkbook() throws Exception {
        byte[] sevenZ = sevenZOf(entry("course-template.xlsm", new byte[MAX_WORKBOOK_BYTES + 1]));

        assertThatThrownBy(() -> reader.read(sevenZ, "course.7z"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("File template vượt quá dung lượng cho phép");
    }

    @Test
    void rejectsOversizedSevenZAudio() throws Exception {
        byte[] sevenZ = sevenZOf(
                entry("course-template.xlsm", new byte[] {1}),
                entry("audio/morning.mp3", new byte[MAX_AUDIO_BYTES + 1]));

        assertThatThrownBy(() -> reader.read(sevenZ, "course.7z"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("File audio vượt quá dung lượng cho phép");
    }

    @Test
    void rejectsXlsxWorkbookBecauseTemplateRequiresVba() throws Exception {
        byte[] zip = zipOf(entry("course-template.xlsx", new byte[] {1}));

        assertThatThrownBy(() -> reader.read(zip, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Gói nhập phải có đúng một file .xlsm")
                .hasMessageContaining("template cần VBA");
    }

    @Test
    void rejectsDuplicateNormalizedPaths() throws Exception {
        byte[] zip = zipOf(
                entry("course-template.xlsm", new byte[] {1}),
                entry("audio/morning.mp3", new byte[] {2}),
                entry("audio/./morning.mp3", new byte[] {3}));

        assertThatThrownBy(() -> reader.read(zip, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("trùng")
                .hasMessageContaining("audio/morning.mp3");
    }

    @Test
    void rejectsUnsupportedArchiveDataWithSupportedExtensions() {
        byte[] unsupported = new byte[] {9, 8, 7};

        assertThatThrownBy(() -> reader.read(unsupported, "course-package.rar"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(".zip")
                .hasMessageContaining(".rar")
                .hasMessageContaining(".7z");
    }

    @Test
    void rejectsZipBytesWhenFilenameSaysRarWithSupportedExtensions() throws Exception {
        byte[] zip = zipOf(entry("course-template.xlsm", new byte[] {1}));

        assertThatThrownBy(() -> reader.read(zip, "course-package.rar"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("không khớp")
                .hasMessageContaining(".zip")
                .hasMessageContaining(".rar")
                .hasMessageContaining(".7z");
    }

    @Test
    void rejectsEmptyZipBecauseWorkbookIsMissing() throws Exception {
        byte[] zip = zipOf();

        assertThatThrownBy(() -> reader.read(zip, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(".xlsm");
    }

    @Test
    void rejectsCorruptZipWithUnsupportedOrCorruptArchiveMessage() {
        byte[] corruptZip = new byte[] {'P', 'K', 1, 2, 3};

        assertThatThrownBy(() -> reader.read(corruptZip, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("không hợp lệ");
    }

    @Test
    void rejectsZipWithReadableLocalEntryButMissingCentralDirectory() throws Exception {
        byte[] zip = zipOf(entry("course-template.xlsm", new byte[] {1, 2, 3}));
        byte[] corruptZip = java.util.Arrays.copyOf(zip, zipCentralDirectoryIndex(zip));

        assertThatThrownBy(() -> reader.read(corruptZip, "course.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("không hợp lệ");
    }

    @Test
    void rejectsSevenZBytesWhenFilenameExtensionDoesNotMatchDetectedFormat() throws Exception {
        byte[] sevenZ = sevenZOf(entry("course-template.xlsm", new byte[] {1}));

        assertThatThrownBy(() -> reader.read(sevenZ, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("không khớp")
                .hasMessageContaining(".7z")
                .hasMessageContaining(".zip")
                .hasMessageContaining(".rar");
    }

    @Test
    void normalizesZipBackslashPaths() throws Exception {
        byte[] zip = zipOf(
                entry("course-template.xlsm", new byte[] {1}),
                entry("audio\\morning.mp3", new byte[] {2}));

        CourseImportPackage result = reader.read(zip, "course-package.zip");

        assertThat(result.audioFiles()).containsOnlyKeys("audio/morning.mp3");
    }

    @Test
    void rejectsDuplicateNormalizedSevenZPaths() throws Exception {
        byte[] sevenZ = sevenZOf(
                entry("course-template.xlsm", new byte[] {1}),
                entry("audio/morning.mp3", new byte[] {2}),
                entry("audio\\morning.mp3", new byte[] {3}));

        assertThatThrownBy(() -> reader.read(sevenZ, "course-package.7z"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("trùng")
                .hasMessageContaining("audio/morning.mp3");
    }

    @Test
    void rejectsSevenZAntiItemAsNonRegularEntry() throws Exception {
        byte[] sevenZ = sevenZWithAntiItem("audio/deleted.mp3");

        assertThatThrownBy(() -> reader.read(sevenZ, "course-package.7z"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("không hợp lệ");
    }

    @Test
    void reportsOffendingPathWhenAudioFileIsOutsideAudioFolder() throws Exception {
        byte[] zip = zipOf(
                entry("course-template.xlsm", new byte[] {1}),
                entry("morning.mp3", new byte[] {2}));

        assertThatThrownBy(() -> reader.read(zip, "course-package.zip"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("File audio phải nằm trong thư mục audio/")
                .hasMessageContaining("morning.mp3");
    }

    @Test
    void rejectsZipBytesWhenFilenameExtensionDoesNotMatchDetectedFormat() throws Exception {
        byte[] zip = zipOf(entry("course-template.xlsm", new byte[] {1}));

        assertThatThrownBy(() -> reader.read(zip, "course-package.rar"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("không khớp")
                .hasMessageContaining(".zip");
    }

    @Test
    void rejectsMalformedRarAfterFilenameValidation() {
        byte[] rar = new byte[] {'R', 'a', 'r', '!', 0x1A, 0x07, 0x00};

        assertThatThrownBy(() -> reader.read(rar, "course-package.rar"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("RAR")
                .hasMessageContaining("không hợp lệ");
    }

    @Test
    void rejectsTruncatedRealRarAsUnsupportedRar() throws Exception {
        byte[] validRar = resourceBytes("/import-archives/course-import.rar");
        byte[] truncatedRar = java.util.Arrays.copyOf(validRar, validRar.length / 2);

        assertThatThrownBy(() -> reader.read(truncatedRar, "course-package.rar"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("RAR")
                .hasMessageContaining("không hợp lệ")
                .hasMessageContaining("không được hỗ trợ");
    }

    @Test
    void rejectsMalformedRarFiveAfterFilenameValidation() {
        byte[] rar = new byte[] {'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00, 0x01};

        assertThatThrownBy(() -> reader.read(rar, "course-package.rar"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("RAR")
                .hasMessageContaining("không hợp lệ")
                .hasMessageContaining("không được hỗ trợ");
    }

    @Test
    void rejectsMalformedSevenZipWithoutFilename() {
        byte[] sevenZip = new byte[] {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

        assertThatThrownBy(() -> reader.read(sevenZip, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Gói nhập không hợp lệ");
    }

    private static ZipInput entry(String path, byte[] bytes) {
        return new ZipInput(path, bytes);
    }

    private static byte[] zipOf(ZipInput... inputs) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (ZipInput input : inputs) {
                zip.putNextEntry(new ZipEntry(input.path()));
                zip.write(input.bytes());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static int zipCentralDirectoryIndex(byte[] zip) {
        for (int index = 0; index < zip.length - 3; index++) {
            if (zip[index] == 'P' && zip[index + 1] == 'K' && zip[index + 2] == 0x01 && zip[index + 3] == 0x02) {
                return index;
            }
        }
        throw new AssertionError("ZIP central directory not found");
    }

    private static byte[] sevenZOf(ZipInput... inputs) throws Exception {
        Path tempArchive = Files.createTempFile("course-import-", ".7z");
        try {
            try (SevenZOutputFile sevenZ = new SevenZOutputFile(tempArchive.toFile())) {
                for (ZipInput input : inputs) {
                    SevenZArchiveEntry entry = new SevenZArchiveEntry();
                    entry.setName(input.path());
                    entry.setSize(input.bytes().length);
                    sevenZ.putArchiveEntry(entry);
                    sevenZ.write(input.bytes());
                    sevenZ.closeArchiveEntry();
                }
            }
            return Files.readAllBytes(tempArchive);
        } finally {
            Files.deleteIfExists(tempArchive);
        }
    }

    private static byte[] sevenZWithAntiItem(String path) throws Exception {
        Path tempArchive = Files.createTempFile("course-import-", ".7z");
        try {
            try (SevenZOutputFile sevenZ = new SevenZOutputFile(tempArchive.toFile())) {
                SevenZArchiveEntry entry = new SevenZArchiveEntry();
                entry.setName(path);
                entry.setAntiItem(true);
                entry.setHasStream(false);
                sevenZ.putArchiveEntry(entry);
                sevenZ.closeArchiveEntry();
            }
            return Files.readAllBytes(tempArchive);
        } finally {
            Files.deleteIfExists(tempArchive);
        }
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (InputStream input = CourseImportArchiveReaderTests.class.getResourceAsStream(path)) {
            assertThat(input).as("test resource %s", path).isNotNull();
            return input.readAllBytes();
        }
    }

    private record ZipInput(String path, byte[] bytes) {}
}
