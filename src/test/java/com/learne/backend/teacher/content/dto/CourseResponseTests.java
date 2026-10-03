package com.learne.backend.teacher.content.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.entity.QuestionRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import org.junit.jupiter.api.Test;

class CourseResponseTests {

    @Test
    void revisionBackedCoursePropagatesRevisionStatusToNestedContent() {
        Course course = new Course();
        course.setStatus(ContentStatus.DRAFT);

        CourseRevision revision = new CourseRevision();
        revision.setStatus(CourseRevisionStatus.PENDING_REVIEW);
        revision.setName("Revision course");
        revision.setType(CourseType.GRAMMAR);
        revision.setLevel("A1");

        TopicRevision topic = new TopicRevision();
        topic.setName("Topic");

        LessonRevision lesson = new LessonRevision();
        lesson.setTitle("Lesson");
        lesson.setSkillType(SkillType.GRAMMAR);

        QuestionRevision question = new QuestionRevision();
        question.setType(QuestionType.MULTIPLE_CHOICE);
        question.setQuestionText("Question?");

        lesson.getQuestions().add(question);
        topic.getLessons().add(lesson);
        revision.getTopics().add(topic);

        CourseResponse response = CourseResponse.fromRevisionBackedCourse(course, revision);

        LessonResponse lessonResponse = response.topics().getFirst().lessons().getFirst();
        assertThat(lessonResponse.status()).isEqualTo(ContentStatus.PENDING_REVIEW);
        assertThat(lessonResponse.questions().getFirst().status()).isEqualTo(ContentStatus.PENDING_REVIEW);
    }
}
