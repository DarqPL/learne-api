package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class CursorQueryCompatibilityTests {

    @Test
    void cursorQueriesDoNotUseNullableCursorBranches() {
        Stream.of(CourseRepository.class, CourseEnrollmentRepository.class)
                .flatMap(repository -> Arrays.stream(repository.getDeclaredMethods()))
                .filter(method -> method.getName().toLowerCase().contains("page")
                        || Arrays.stream(method.getParameters()).anyMatch(parameter -> parameter.getName().startsWith("cursor")))
                .map(method -> method.getAnnotation(Query.class))
                .filter(Query.class::isInstance)
                .map(Query.class::cast)
                .map(Query::value)
                .forEach(query -> assertThat(query)
                        .doesNotContainPattern(":cursor\\w+\\s+is\\s+null"));
    }
}
