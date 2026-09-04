package com.learne.backend.student.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learne.backend.domain.auth.User;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.student.learning.ai.AiCourseRecommendationRequest;
import com.learne.backend.student.learning.ai.AiCourseRecommendationResponse;
import com.learne.backend.student.learning.ai.AiLearningPathClient;
import com.learne.backend.student.learning.dto.CourseRecommendationPathResponse;
import com.learne.backend.student.learning.dto.GenerateCourseRecommendationsRequest;
import com.learne.backend.student.learning.entity.CourseEnrollment;
import com.learne.backend.student.learning.entity.CourseRecommendationPath;
import com.learne.backend.student.learning.entity.PlacementAttempt;
import com.learne.backend.student.learning.entity.PlacementAttemptAnswer;
import com.learne.backend.student.learning.entity.PlacementAttemptQuestion;
import com.learne.backend.student.learning.enums.EnrollmentStatus;
import com.learne.backend.student.learning.enums.LearningPathSource;
import com.learne.backend.student.learning.enums.LearningPathStatus;
import com.learne.backend.student.learning.enums.PlacementAttemptStatus;
import com.learne.backend.student.learning.repository.CourseEnrollmentRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationItemRepository;
import com.learne.backend.student.learning.repository.CourseRecommendationPathRepository;
import com.learne.backend.student.learning.repository.PlacementAttemptRepository;
import com.learne.backend.student.learning.service.StudentCourseRecommendationService;
import com.learne.backend.teacher.content.entity.Course;
import com.learne.backend.teacher.content.entity.Lesson;
import com.learne.backend.teacher.content.entity.Question;
import com.learne.backend.teacher.content.entity.Topic;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.CourseType;
import com.learne.backend.teacher.content.enums.QuestionType;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class StudentCourseRecommendationServiceTests {
    @Mock private CourseRepository courseRepository;
    @Mock private CourseEnrollmentRepository courseEnrollmentRepository;
    @Mock private PlacementAttemptRepository placementAttemptRepository;
    @Mock private CourseRecommendationPathRepository courseRecommendationPathRepository;
    @Mock private CourseRecommendationItemRepository courseRecommendationItemRepository;
    @Mock private AiLearningPathClient aiLearningPathClient;

    @Test
    void generateArchivesPreviousActivePathAndMapsRecommendedCourses() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course grammarCourse = course(10L, "Grammar Course", teacher, CourseType.GRAMMAR);
        Course vocabularyCourse = course(11L, "Vocabulary Course", teacher, CourseType.VOCABULARY);
        CourseRecommendationPath existingPath = recommendationPath(30L, student);
        PlacementAttempt attempt = placementAttempt(40L, student);
        CourseEnrollment enrollment = enrollment(50L, student, grammarCourse, EnrollmentStatus.ACTIVE);

        when(placementAttemptRepository.findByUserAndStatus(student, PlacementAttemptStatus.SUBMITTED))
                .thenReturn(List.of(attempt));
        when(courseRepository.findApprovedCourseRecommendationCandidates(ContentStatus.APPROVED))
                .thenReturn(List.of(grammarCourse, vocabularyCourse));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student))
                .thenReturn(List.of(enrollment));
        when(aiLearningPathClient.generateCourseRecommendations(any(AiCourseRecommendationRequest.class)))
                .thenReturn(new AiCourseRecommendationResponse(List.of("grammar"), List.of(
                        new AiCourseRecommendationResponse.RecommendationPayload(
                                grammarCourse.getId(), new BigDecimal("0.9200"), "Grammar fit"),
                        new AiCourseRecommendationResponse.RecommendationPayload(
                                vocabularyCourse.getId(), new BigDecimal("0.8100"), "Vocabulary fit"))));
        when(courseRecommendationPathRepository.findByUserAndStatusForUpdate(student, LearningPathStatus.ACTIVE))
                .thenReturn(List.of(existingPath));
        when(courseRecommendationPathRepository.save(any(CourseRecommendationPath.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CourseRecommendationPathResponse response = service().generate(student, new GenerateCourseRecommendationsRequest(8));

        assertThat(existingPath.getStatus()).isEqualTo(LearningPathStatus.ARCHIVED);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.source()).isEqualTo("AI_SERVICE");
        assertThat(response.weaknesses()).containsExactly("grammar");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().getFirst().courseId()).isEqualTo(grammarCourse.getId());
        assertThat(response.items().getFirst().courseTitle()).isEqualTo("Grammar Course");
        assertThat(response.items().getFirst().enrolled()).isTrue();
        assertThat(response.items().getFirst().enrollmentStatus()).isEqualTo("ACTIVE");
        assertThat(response.items().getFirst().sequenceOrder()).isEqualTo(1);
        assertThat(response.items().getFirst().reason()).isEqualTo("Grammar fit");
    }

    @Test
    void generateRejectsInvalidAiCourseIds() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "Grammar Course", teacher, CourseType.GRAMMAR);
        when(placementAttemptRepository.findByUserAndStatus(student, PlacementAttemptStatus.SUBMITTED))
                .thenReturn(List.of(placementAttempt(40L, student)));
        when(courseRepository.findApprovedCourseRecommendationCandidates(ContentStatus.APPROVED)).thenReturn(List.of(course));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of());
        when(aiLearningPathClient.generateCourseRecommendations(any(AiCourseRecommendationRequest.class)))
                .thenReturn(new AiCourseRecommendationResponse(List.of(), List.of(
                        new AiCourseRecommendationResponse.RecommendationPayload(999L, BigDecimal.ONE, "Invalid"))));

        assertThatThrownBy(() -> service().generate(student, new GenerateCourseRecommendationsRequest(8)))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void generateIncludesWeakKnowledgePointsInAiSkillStats() throws Exception {
        User student = user(1L, "Student User", UserRole.STUDENT);
        User teacher = user(2L, "Teacher User", UserRole.TEACHER);
        Course course = course(10L, "Grammar Course", teacher, CourseType.GRAMMAR);
        PlacementAttempt attempt = placementAttempt(40L, student);
        addPlacementAnswer(attempt, 100L, "GRAMMAR", "present simple", false, 0);
        addPlacementAnswer(attempt, 101L, "GRAMMAR", "present simple", false, 0);
        addPlacementAnswer(attempt, 102L, "GRAMMAR", " ", false, 0);
        addPlacementAnswer(attempt, 103L, "GRAMMAR", "articles", true, 100);
        when(placementAttemptRepository.findByUserAndStatus(student, PlacementAttemptStatus.SUBMITTED))
                .thenReturn(List.of(attempt));
        when(courseRepository.findApprovedCourseRecommendationCandidates(ContentStatus.APPROVED)).thenReturn(List.of(course));
        when(courseEnrollmentRepository.findByUserWithDetailsOrderByEnrolledAtDescIdDesc(student)).thenReturn(List.of());
        when(aiLearningPathClient.generateCourseRecommendations(any(AiCourseRecommendationRequest.class)))
                .thenReturn(new AiCourseRecommendationResponse(List.of(), List.of(
                        new AiCourseRecommendationResponse.RecommendationPayload(course.getId(), BigDecimal.ONE, "Fit"))));
        when(courseRecommendationPathRepository.findByUserAndStatusForUpdate(student, LearningPathStatus.ACTIVE)).thenReturn(List.of());
        when(courseRecommendationPathRepository.save(any(CourseRecommendationPath.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().generate(student, new GenerateCourseRecommendationsRequest(8));

        ArgumentCaptor<AiCourseRecommendationRequest> requestCaptor = ArgumentCaptor.forClass(AiCourseRecommendationRequest.class);
        verify(aiLearningPathClient).generateCourseRecommendations(requestCaptor.capture());
        assertThat(requestCaptor.getValue().student())
                .isEqualTo(new AiCourseRecommendationRequest.StudentPayload(student.getId(), "B1"));
        assertThat(requestCaptor.getValue().skillStats())
                .anySatisfy(stat -> {
                    assertThat(stat.skill()).isEqualTo("GRAMMAR");
                    assertThat(stat.weakKnowledgePoints()).containsExactly("present simple");
                });
    }

    private StudentCourseRecommendationService service() {
        return new StudentCourseRecommendationService(
                courseRepository,
                courseEnrollmentRepository,
                placementAttemptRepository,
                courseRecommendationPathRepository,
                courseRecommendationItemRepository,
                aiLearningPathClient,
                new ObjectMapper(),
                null);
    }

    private User user(Long id, String fullName, UserRole role) throws Exception {
        User user = new User();
        setField(user, "id", id);
        user.setEmail(fullName.toLowerCase().replace(' ', '.') + "@example.com");
        user.setFullName(fullName);
        user.setRole(role);
        return user;
    }

    private Course course(Long id, String name, User teacher, CourseType type) throws Exception {
        Course course = new Course();
        setField(course, "id", id);
        course.setName(name);
        course.setDescription(name + " description");
        course.setTeacher(teacher);
        course.setType(type);
        course.setLevel("A1");
        course.setStatus(ContentStatus.APPROVED);
        Topic topic = new Topic();
        topic.setCourse(course);
        course.getTopics().add(topic);
        Lesson lesson = new Lesson();
        lesson.setTopic(topic);
        lesson.setTeacher(teacher);
        lesson.setTitle(name + " lesson");
        lesson.setSkillType(SkillType.GRAMMAR);
        lesson.setPlacementTest(false);
        lesson.setStatus(ContentStatus.APPROVED);
        topic.getLessons().add(lesson);
        return course;
    }

    private CourseEnrollment enrollment(Long id, User student, Course course, EnrollmentStatus status) throws Exception {
        CourseEnrollment enrollment = new CourseEnrollment();
        setField(enrollment, "id", id);
        enrollment.setUser(student);
        enrollment.setCourse(course);
        enrollment.setStatus(status);
        return enrollment;
    }

    private PlacementAttempt placementAttempt(Long id, User student) throws Exception {
        PlacementAttempt attempt = new PlacementAttempt();
        setField(attempt, "id", id);
        attempt.setUser(student);
        attempt.setStatus(PlacementAttemptStatus.SUBMITTED);
        attempt.setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
        attempt.setSubmittedAt(Instant.parse("2026-01-01T00:10:00Z"));
        attempt.setExpiresAt(Instant.parse("2026-01-01T00:20:00Z"));
        attempt.setScorePercent(80);
        return attempt;
    }

    private void addPlacementAnswer(PlacementAttempt attempt, Long questionId, String skill, String knowledgePoint,
            boolean correct, int scorePercent) throws Exception {
        Question question = new Question();
        setField(question, "id", questionId);
        question.setType(QuestionType.MULTIPLE_CHOICE);
        question.setQuestionText("Question " + questionId);
        question.setKnowledgePoint(knowledgePoint);
        PlacementAttemptQuestion attemptQuestion = new PlacementAttemptQuestion();
        attemptQuestion.setPlacementAttempt(attempt);
        attemptQuestion.setQuestion(question);
        attemptQuestion.setSkill(skill);
        attemptQuestion.setSequenceOrder(attempt.getQuestions().size() + 1);
        attempt.getQuestions().add(attemptQuestion);
        PlacementAttemptAnswer answer = new PlacementAttemptAnswer();
        answer.setPlacementAttempt(attempt);
        answer.setQuestion(question);
        answer.setCorrect(correct);
        answer.setScorePercent(scorePercent);
        attempt.getAnswers().add(answer);
    }

    private CourseRecommendationPath recommendationPath(Long id, User student) throws Exception {
        CourseRecommendationPath path = new CourseRecommendationPath();
        setField(path, "id", id);
        path.setUser(student);
        path.setStatus(LearningPathStatus.ACTIVE);
        path.setSource(LearningPathSource.AI_SERVICE);
        path.setGeneratedAt(Instant.parse("2026-01-01T00:00:00Z"));
        path.setWeaknessesJson("[]");
        return path;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
