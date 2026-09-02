package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;

class LearningPathMappingTests {

    @Test
    void learningPathEntityMapsLearningPathsTable() throws Exception {
        Class<?> entity = Class.forName("com.learne.backend.student.learning.entity.LearningPath");

        assertThat(entity).isNotNull();
        assertThat(entity.getAnnotation(Entity.class)).isNotNull();
        assertThat(entity.getAnnotation(Table.class).name()).isEqualTo("learning_paths");
        assertThat(Class.forName("com.learne.backend.student.learning.enums.LearningPathStatus").getEnumConstants())
                .extracting(Object::toString)
                .containsExactly("ACTIVE", "ARCHIVED");
        assertThat(Class.forName("com.learne.backend.student.learning.enums.LearningPathSource").getEnumConstants())
                .extracting(Object::toString)
                .containsExactly("AI_SERVICE", "FALLBACK_RULE");

        assertManyToOne(entity.getDeclaredField("user"), "user_id");
        assertEnumColumn(entity.getDeclaredField("status"), "", 50);
        assertTextColumn(entity.getDeclaredField("weaknessesJson"), "weaknesses_json");
        assertEnumColumn(entity.getDeclaredField("source"), "", 50);
        assertColumn(entity.getDeclaredField("generatedAt"), "generated_at", true);
        assertColumn(entity.getDeclaredField("createdAt"), "created_at", true);
        assertColumn(entity.getDeclaredField("updatedAt"), "updated_at", true);

        Field items = entity.getDeclaredField("items");
        OneToMany oneToMany = items.getAnnotation(OneToMany.class);
        assertThat(oneToMany.mappedBy()).isEqualTo("path");
        assertThat(oneToMany.cascade()).containsExactly(CascadeType.ALL);
        assertThat(oneToMany.orphanRemoval()).isTrue();
        assertThat(items.getAnnotation(OrderBy.class).value()).isEqualTo("sequenceOrder ASC");
        assertThat(entity.getConstructor().newInstance()).extracting("items").asList().isEmpty();
    }

    @Test
    void learningPathItemEntityMapsLearningPathItemsTable() throws Exception {
        Class<?> entity = Class.forName("com.learne.backend.student.learning.entity.LearningPathItem");

        assertThat(entity).isNotNull();
        assertThat(entity.getAnnotation(Entity.class)).isNotNull();
        assertThat(entity.getAnnotation(Table.class).name()).isEqualTo("learning_path_items");
        assertManyToOne(entity.getDeclaredField("path"), "path_id");
        assertManyToOne(entity.getDeclaredField("lesson"), "lesson_id");
        assertColumn(entity.getDeclaredField("sequenceOrder"), "sequence_order", true);
        assertTextColumn(entity.getDeclaredField("reason"), "");
        assertThat(entity.getDeclaredField("score").getType()).isEqualTo(BigDecimal.class);
        Column score = entity.getDeclaredField("score").getAnnotation(Column.class);
        assertThat(score.precision()).isEqualTo(5);
        assertThat(score.scale()).isEqualTo(4);
        assertThat(score.nullable()).isFalse();
        assertColumn(entity.getDeclaredField("completed"), "", true);
        assertColumn(entity.getDeclaredField("createdAt"), "created_at", true);
        assertColumn(entity.getDeclaredField("updatedAt"), "updated_at", true);
    }

    @Test
    void repositoriesDeclareRequestedQueriesAndEntityGraphs() throws Exception {
        Class<?> pathRepository = Class.forName("com.learne.backend.student.learning.repository.LearningPathRepository");
        Method latestPath = pathRepository.getDeclaredMethod(
                "findFirstByUserAndStatusOrderByGeneratedAtDesc",
                Class.forName("com.learne.backend.domain.auth.User"),
                Class.forName("com.learne.backend.student.learning.enums.LearningPathStatus"));
        assertThat(latestPath.getAnnotation(EntityGraph.class)).isNull();

        Class<?> itemRepository = Class.forName("com.learne.backend.student.learning.repository.LearningPathItemRepository");
        Method orderedItems = itemRepository.getDeclaredMethod(
                "findByPathOrderBySequenceOrderAsc",
                Class.forName("com.learne.backend.student.learning.entity.LearningPath"));
        assertThat(orderedItems.getAnnotation(EntityGraph.class).attributePaths())
                .containsExactly("lesson", "lesson.topic", "lesson.topic.course", "lesson.topic.course.teacher");
    }

    private void assertManyToOne(Field field, String joinColumnName) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        assertThat(manyToOne.fetch()).isEqualTo(FetchType.LAZY);
        assertThat(manyToOne.optional()).isFalse();
        assertThat(field.getAnnotation(JoinColumn.class).name()).isEqualTo(joinColumnName);
        assertThat(field.getAnnotation(JoinColumn.class).nullable()).isFalse();
    }

    private void assertEnumColumn(Field field, String columnName, int length) {
        assertThat(field.getAnnotation(Enumerated.class).value()).isEqualTo(EnumType.STRING);
        Column column = field.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isFalse();
        assertThat(column.length()).isEqualTo(length);
    }

    private void assertTextColumn(Field field, String columnName) {
        Column column = field.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.columnDefinition()).isEqualTo("TEXT");
    }

    private void assertColumn(Field field, String columnName, boolean nullableFalse) {
        Column column = field.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo(columnName);
        assertThat(column.nullable()).isEqualTo(!nullableFalse);
    }
}
