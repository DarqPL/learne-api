package com.learne.backend.teacher.content.submission;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SubmissionPublicSurfaceTests {
    @Test void noWholeGraphSynchronousEntryPointRemains() {
        assertThat(com.learne.backend.teacher.content.service.TeacherContentService.class.getMethods())
            .extracting(java.lang.reflect.Method::getName).doesNotContain("submitCourse");
        assertThat(com.learne.backend.teacher.content.enrichment.CourseReviewEnrichmentService.class.getMethods())
            .extracting(java.lang.reflect.Method::getName).doesNotContain("buildSnapshot","enrichSnapshot","applyEnrichment");
    }
}
