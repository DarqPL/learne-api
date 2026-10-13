package com.learne.backend.teacher.content.importexport.template;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.learne.backend.shared.api.ApiException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CourseExportPackageBuilderTests {
    @Test
    void rejectsNullAudioList() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("audio");
    }

    @Test
    void rejectsNullAudioElement() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, Arrays.asList((ExportedAudioFile) null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("audio");
    }

    @Test
    void rejectsNullWorkbookBytes() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, null, List.of()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Workbook");
    }

    @Test
    void rejectsNullAudioBytes() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("audio/morning.mp3", "audio/mpeg", null))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bytes");
    }

    @Test
    void rejectsAudioPathTraversal() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("audio/../outside.mp3", "audio/mpeg", new byte[] {2}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Đường dẫn");
    }

    @Test
    void rejectsDuplicateAudioPathsAfterSeparatorNormalization() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("audio/morning.mp3", "audio/mpeg", new byte[] {2}),
                new ExportedAudioFile("audio/morning.mp3", "audio/mpeg", new byte[] {3}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("trùng");
    }

    @Test
    void rejectsAudioPathContainingBackslash() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("audio\\morning.mp3", "audio/mpeg", new byte[] {2}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Đường dẫn");
    }

    @Test
    void rejectsAbsoluteDriveAudioPath() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("C:\\outside.mp3", "audio/mpeg", new byte[] {2}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Đường dẫn");
    }

    @Test
    void rejectsAudioPathWithEmptySegment() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("audio//morning.mp3", "audio/mpeg", new byte[] {2}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Đường dẫn");
    }

    @Test
    void rejectsAudioPathThatDuplicatesWorkbookEntry() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("course-42.xlsm", "application/octet-stream", new byte[] {2}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("trùng")
                .hasMessageContaining("course-42.xlsm");
    }

    @Test
    void rejectsAudioEntryOutsideAudioDirectory() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("documents/morning.mp3", "audio/mpeg", new byte[] {2}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("audio/");
    }

    @Test
    void rejectsAudioDirectoryWithoutFileName() {
        CourseExportPackageBuilder builder = new CourseExportPackageBuilder();

        assertThatThrownBy(() -> builder.build(42L, new byte[] {1}, List.of(
                new ExportedAudioFile("audio", "audio/mpeg", new byte[] {2}))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("audio/");
    }
}
