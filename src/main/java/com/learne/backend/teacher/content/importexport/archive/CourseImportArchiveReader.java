package com.learne.backend.teacher.content.importexport.archive;

import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.importexport.security.AudioFileValidator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.simple.ISimpleInArchiveItem;
import net.sf.sevenzipjbinding.util.ByteArrayStream;

@Component
public class CourseImportArchiveReader {
    private static final int MAX_ENTRIES = 300;
    private static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
    private static final long MAX_TOTAL_UNCOMPRESSED_BYTES = 150L * 1024L * 1024L;
    private static final long MAX_WORKBOOK_BYTES = 20L * 1024L * 1024L;
    private static final int MAX_AUDIO_FILES = 100;
    private static final String INVALID_ARCHIVE_PATH = "Đường dẫn trong gói nhập không hợp lệ";
    private static final String SUPPORTED_ARCHIVES = ".zip, .rar, .7z";
    private static final String XLSM_ERROR = "Gói nhập phải có đúng một file .xlsm được tải từ template LearnE. "
            + "File .xlsx/.xls không được hỗ trợ vì template cần VBA.";

    public CourseImportPackage read(byte[] archiveBytes, String filename) {
        if (archiveBytes == null || archiveBytes.length == 0) {
            throw badRequest("Gói nhập không hợp lệ");
        }
        if (archiveBytes.length > MAX_UPLOAD_BYTES) {
            throw badRequest("Gói nhập vượt quá dung lượng cho phép");
        }

        ArchiveFormat format = detectFormat(archiveBytes);
        validateFilename(filename, format);
        if (format == ArchiveFormat.RAR) {
            return readRar(archiveBytes);
        }

        if (format == ArchiveFormat.SEVEN_ZIP) {
            return readSevenZip(archiveBytes);
        }
        return readZip(archiveBytes);
    }

    private ArchiveFormat detectFormat(byte[] archiveBytes) {
        if (startsWith(archiveBytes, new byte[] {'P', 'K'})) {
            return ArchiveFormat.ZIP;
        }
        if (startsWith(archiveBytes, new byte[] {'R', 'a', 'r', '!'})) {
            return ArchiveFormat.RAR;
        }
        if (startsWith(archiveBytes, new byte[] {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C})) {
            return ArchiveFormat.SEVEN_ZIP;
        }
        throw invalidArchive();
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private void validateFilename(String filename, ArchiveFormat detectedFormat) {
        if (filename == null || filename.isBlank()) {
            return;
        }
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        if (!lowerFilename.endsWith(detectedFormat.extension())) {
            throw badRequest("Phần mở rộng file không khớp với định dạng gói nhập "
                    + detectedFormat.extension() + ". Định dạng được hỗ trợ: " + SUPPORTED_ARCHIVES);
        }
    }

    private CourseImportPackage readZip(byte[] zipBytes) {
        ArchiveAccumulator accumulator = new ArchiveAccumulator();

        try {
            validateZip(zipBytes);
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                boolean hasEntries = false;
                while ((entry = zip.getNextEntry()) != null) {
                    hasEntries = true;
                    accumulator.addEntry(entry.getName(), entry.isDirectory(), zip::read);
                    zip.closeEntry();
                }
                if (!hasEntries && !hasZipEndOfCentralDirectory(zipBytes)) {
                    throw invalidArchive();
                }
            }
        } catch (IOException e) {
            throw invalidArchive();
        }

        return accumulator.toImportPackage();
    }

    private void validateZip(byte[] zipBytes) throws IOException {
        Path tempArchive = Files.createTempFile("course-import-", ".zip");
        try {
            Files.write(tempArchive, zipBytes);
            try (ZipFile ignored = new ZipFile(tempArchive.toFile())) {
                // Opening ZipFile forces the JDK to parse the central directory/EOCD.
            }
        } finally {
            Files.deleteIfExists(tempArchive);
        }
    }

    private boolean hasZipEndOfCentralDirectory(byte[] zipBytes) {
        for (int index = zipBytes.length - 4; index >= 0; index--) {
            if (zipBytes[index] == 'P' && zipBytes[index + 1] == 'K' && zipBytes[index + 2] == 0x05
                    && zipBytes[index + 3] == 0x06) {
                return true;
            }
        }
        return false;
    }

    private CourseImportPackage readRar(byte[] rarBytes) {
        ArchiveAccumulator accumulator = new ArchiveAccumulator();

        if (rarBytes.length <= 7) {
            throw unsupportedRar("Gói nhập RAR không hợp lệ hoặc không được hỗ trợ");
        }

        try (ByteArrayStream stream = new ByteArrayStream(rarBytes, false);
                IInArchive rar = SevenZip.openInArchive(rarArchiveFormat(rarBytes), stream)) {
            validateRarArchive(rar);
            for (ISimpleInArchiveItem entry : rar.getSimpleInterface().getArchiveItems()) {
                validateRarEntry(rar, entry);
                accumulator.addExtractedEntry(entry.getPath(), entry.isFolder(),
                        (currentTotalBytes, entryLimit) -> extractRarEntry(entry, currentTotalBytes, entryLimit));
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw unsupportedRar("Gói nhập RAR không hợp lệ hoặc không được hỗ trợ");
        }

        return accumulator.toImportPackage();
    }

    private void validateRarArchive(IInArchive archive) throws SevenZipException {
        Object errorFlags = archive.getArchiveProperty(PropID.ERROR_FLAGS);
        if (errorFlags instanceof Number number && number.longValue() != 0L) {
            throw unsupportedRar("Gói nhập RAR không hợp lệ hoặc không được hỗ trợ");
        }
    }

    private net.sf.sevenzipjbinding.ArchiveFormat rarArchiveFormat(byte[] rarBytes) {
        if (rarBytes.length > 6 && rarBytes[6] == 0x01) {
            return net.sf.sevenzipjbinding.ArchiveFormat.RAR5;
        }
        return net.sf.sevenzipjbinding.ArchiveFormat.RAR;
    }

    private void validateRarEntry(IInArchive archive, ISimpleInArchiveItem entry) throws SevenZipException {
        if (entry.isEncrypted()) {
            throw unsupportedRar("Gói nhập RAR được mã hóa hoặc yêu cầu mật khẩu không được hỗ trợ");
        }
        int index = entry.getItemIndex();
        if (booleanProperty(archive, index, PropID.SPLIT_BEFORE) || booleanProperty(archive, index, PropID.SPLIT_AFTER)) {
            throw unsupportedRar("Gói nhập RAR nhiều volume không được hỗ trợ");
        }
        if (booleanProperty(archive, index, PropID.IS_ALT_STREAM)
                || booleanProperty(archive, index, PropID.SYM_LINK)
                || booleanProperty(archive, index, PropID.HARD_LINK)) {
            throw invalidArchive();
        }
    }

    private boolean booleanProperty(IInArchive archive, int index, PropID property) throws SevenZipException {
        return Boolean.TRUE.equals(archive.getProperty(index, property));
    }

    private EntryBytes extractRarEntry(ISimpleInArchiveItem entry, long currentTotalBytes, EntryLimit entryLimit)
            throws SevenZipException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long[] totalBytes = {currentTotalBytes};
        long[] entryBytes = {0L};
        ExtractOperationResult result = entry.extractSlow(data -> {
            entryBytes[0] += data.length;
            if (entryLimit != null && entryBytes[0] > entryLimit.maxBytes()) {
                throw badRequest(entryLimit.message());
            }
            totalBytes[0] += data.length;
            if (totalBytes[0] > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw badRequest("Gói nhập vượt quá dung lượng cho phép");
            }
            output.write(data, 0, data.length);
            return data.length;
        });
        if (result == ExtractOperationResult.WRONG_PASSWORD) {
            throw unsupportedRar("Gói nhập RAR được mã hóa hoặc yêu cầu mật khẩu không được hỗ trợ");
        }
        if (result != ExtractOperationResult.OK) {
            throw unsupportedRar("Gói nhập RAR không hợp lệ hoặc không được hỗ trợ");
        }
        return new EntryBytes(output.toByteArray(), totalBytes[0]);
    }

    private CourseImportPackage readSevenZip(byte[] sevenZipBytes) {
        ArchiveAccumulator accumulator = new ArchiveAccumulator();

        try (SeekableInMemoryByteChannel channel = new SeekableInMemoryByteChannel(sevenZipBytes);
                SevenZFile sevenZip = SevenZFile.builder().setSeekableByteChannel(channel).get()) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZip.getNextEntry()) != null) {
                if (entry.isAntiItem() || (!entry.isDirectory() && !entry.hasStream())) {
                    throw invalidArchive();
                }
                accumulator.addEntry(entry.getName(), entry.isDirectory(), sevenZip::read);
            }
        } catch (IOException e) {
            throw badRequest("Gói nhập không hợp lệ");
        }

        return accumulator.toImportPackage();
    }

    private EntryBytes readEntry(EntryReader reader, long currentTotalBytes, EntryLimit entryLimit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long totalBytes = currentTotalBytes;
        long entryBytes = 0;
        int read;
        while ((read = reader.read(buffer)) != -1) {
            entryBytes += read;
            if (entryLimit != null && entryBytes > entryLimit.maxBytes()) {
                throw badRequest(entryLimit.message());
            }
            totalBytes += read;
            if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                throw badRequest("Gói nhập vượt quá dung lượng cho phép");
            }
            output.write(buffer, 0, read);
        }
        return new EntryBytes(output.toByteArray(), totalBytes);
    }

    private String safePath(String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0) {
            throw badRequest(INVALID_ARCHIVE_PATH);
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\") || entryName.matches("^[A-Za-z]:.*")) {
            throw badRequest(INVALID_ARCHIVE_PATH);
        }

        String normalizedSeparators = entryName.replace('\\', '/');
        String[] segments = normalizedSeparators.split("/", -1);
        List<String> safeSegments = new ArrayList<>();
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.equals("..") || (segment.isBlank() && index != segments.length - 1)) {
                throw badRequest(INVALID_ARCHIVE_PATH);
            }
            if (!segment.isBlank() && !segment.equals(".")) {
                safeSegments.add(segment);
            }
        }
        if (safeSegments.isEmpty()) {
            throw badRequest(INVALID_ARCHIVE_PATH);
        }
        return String.join("/", safeSegments);
    }

    private String contentType(String path) {
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lowerPath.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lowerPath.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lowerPath.endsWith(".ogg")) {
            return "audio/ogg";
        }
        return "application/octet-stream";
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    private ApiException invalidArchive() {
        return badRequest("Gói nhập không hợp lệ. Định dạng được hỗ trợ: " + SUPPORTED_ARCHIVES);
    }

    private ApiException unsupportedRar(String message) {
        return badRequest(message);
    }

    @FunctionalInterface
    private interface EntryReader {
        int read(byte[] buffer) throws IOException;
    }

    private class ArchiveAccumulator {
        private byte[] workbook;
        private final Map<String, CourseImportPackage.ImportFile> audioFiles = new LinkedHashMap<>();
        private final Set<String> seenPaths = new HashSet<>();
        private int entryCount;
        private long totalBytes;

        private void addEntry(String entryName, boolean directory, EntryReader reader) throws IOException {
            String path = safePath(entryName);
            if (!seenPaths.add(path)) {
                throw badRequest("Đường dẫn trong gói nhập bị trùng: " + path);
            }
            entryCount++;
            if (entryCount > MAX_ENTRIES) {
                throw badRequest("Gói nhập vượt quá số lượng file cho phép");
            }

            if (directory) {
                return;
            }

            EntryBytes entryBytes = readEntry(reader, totalBytes, entryLimit(path));
            totalBytes = entryBytes.totalBytes();
            addFile(path, entryBytes.bytes());
        }

        private void addExtractedEntry(String entryName, boolean directory, EntryExtractor extractor)
                throws SevenZipException {
            String path = safePath(entryName);
            if (!seenPaths.add(path)) {
                throw badRequest("Đường dẫn trong gói nhập bị trùng: " + path);
            }
            entryCount++;
            if (entryCount > MAX_ENTRIES) {
                throw badRequest("Gói nhập vượt quá số lượng file cho phép");
            }

            if (directory) {
                return;
            }

            EntryBytes entryBytes = extractor.extract(totalBytes, entryLimit(path));
            totalBytes = entryBytes.totalBytes();
            addFile(path, entryBytes.bytes());
        }

        private EntryLimit entryLimit(String path) {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerPath.endsWith(".xlsm")) {
                return new EntryLimit(MAX_WORKBOOK_BYTES, "File template vượt quá dung lượng cho phép");
            }
            if (path.startsWith("audio/")) {
                return new EntryLimit(AudioFileValidator.MAX_AUDIO_BYTES, "File audio vượt quá dung lượng cho phép");
            }
            return null;
        }

        private void addFile(String path, byte[] bytes) {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (lowerPath.endsWith(".xlsx") || lowerPath.endsWith(".xls")) {
                throw badRequest(XLSM_ERROR);
            }
            if (lowerPath.endsWith(".xlsm")) {
                if (workbook != null) {
                    throw badRequest(XLSM_ERROR);
                }
                if (bytes.length > MAX_WORKBOOK_BYTES) {
                    throw badRequest("File template vượt quá dung lượng cho phép");
                }
                workbook = bytes;
            } else if (path.startsWith("audio/")) {
                if (audioFiles.size() >= MAX_AUDIO_FILES) {
                    throw badRequest("Gói nhập vượt quá số lượng file audio cho phép");
                }
                if (bytes.length > AudioFileValidator.MAX_AUDIO_BYTES) {
                    throw badRequest("File audio vượt quá dung lượng cho phép");
                }
                audioFiles.put(path, new CourseImportPackage.ImportFile(path, contentType(path), bytes));
            } else {
                throw badRequest("File audio phải nằm trong thư mục audio/: " + path);
            }
        }

        private CourseImportPackage toImportPackage() {
            if (workbook == null) {
                throw badRequest(XLSM_ERROR);
            }
            return new CourseImportPackage(workbook, Map.copyOf(audioFiles));
        }
    }

    private enum ArchiveFormat {
        ZIP(".zip"),
        RAR(".rar"),
        SEVEN_ZIP(".7z");

        private final String extension;

        ArchiveFormat(String extension) {
            this.extension = extension;
        }

        private String extension() {
            return extension;
        }
    }

    private record EntryBytes(byte[] bytes, long totalBytes) {}

    private record EntryLimit(long maxBytes, String message) {}

    @FunctionalInterface
    private interface EntryExtractor {
        EntryBytes extract(long currentTotalBytes, EntryLimit entryLimit) throws SevenZipException;
    }
}
