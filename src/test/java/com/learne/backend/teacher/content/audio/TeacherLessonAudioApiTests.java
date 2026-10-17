package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learne.backend.cache.CacheInvalidationService;
import com.learne.backend.domain.auth.UserRepository;
import com.learne.backend.domain.auth.UserRole;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.storage.dto.StorageResourceType;
import com.learne.backend.storage.dto.StorageUploadResult;
import com.learne.backend.storage.service.StorageService;
import com.learne.backend.teacher.content.audio.service.LessonAudioPersistenceService;
import com.learne.backend.teacher.content.audio.service.LessonAudioPostCommitService;
import com.learne.backend.teacher.content.audio.service.LessonAudioReadService;
import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointClient;
import com.learne.backend.teacher.content.entity.CourseRevision;
import com.learne.backend.teacher.content.entity.TopicRevision;
import com.learne.backend.teacher.content.entity.LessonRevision;
import com.learne.backend.teacher.content.enums.CourseRevisionStatus;
import com.learne.backend.teacher.content.enums.ContentStatus;
import com.learne.backend.teacher.content.enums.SkillType;
import com.learne.backend.teacher.content.repository.CourseRepository;
import com.learne.backend.teacher.content.repository.LessonRepository;
import com.learne.backend.teacher.content.importexport.security.ClamAvClient;
import com.learne.backend.teacher.content.repository.CourseRevisionRepository;
import com.learne.backend.teacher.content.service.TeacherContentService;
import com.learne.backend.testsupport.TestDatabaseCleaner;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.test.web.servlet.ResultActions;

@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = "app.cloudinary.cloud-name=test-cloud")
class TeacherLessonAudioApiTests {
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TeacherContentService teacherContentService;

    @Autowired
    private CourseRevisionRepository courseRevisionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StorageService storageService;

    @MockitoBean
    private ClamAvClient clamAvClient;

    @MockitoBean
    private CourseKnowledgePointClient courseKnowledgePointClient;

    private LessonAudioTestFixture fixture;

    @Autowired private CourseRepository courses;
    @Autowired private LessonRepository lessons;
    @Autowired private LessonAudioReadService reads;
    private final ObjectMapper json = new ObjectMapper();
    @MockitoBean private CacheInvalidationService cache;
    @MockitoSpyBean private LessonAudioPersistenceService audioPersistence;
    @MockitoSpyBean private LessonAudioPostCommitService audioPostCommit;

    @BeforeEach
    void setUp() {
        TestDatabaseCleaner.clean(jdbcTemplate);
        fixture = new LessonAudioTestFixture(userRepository, passwordEncoder, teacherContentService,
                courseRevisionRepository, transactionManager, mockMvc);
    }

    @Test
    void teacherCanDiscoverRevisionAudioTargetsForDraftCourse() throws Exception {
        var draft = fixture.draft();

        mockMvc.perform(get("/api/teacher/content/courses/{courseId}/audio-targets", draft.courseId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisionId").value(draft.revisionId()))
                .andExpect(jsonPath("$.lessons[0].lessonRevisionId").value(draft.revisionLessonId()))
                .andExpect(jsonPath("$.lessons[0].sourceLessonId").value(draft.liveLessonId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void discoveryReturnsWholeRevisionTreeWithReimportMismatchAndStableOrdering(boolean published) throws Exception {
        var draft = fixture.draft();
        long[] ids = new long[5];
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var course = courses.findById(draft.courseId()).orElseThrow();
            if (published) {
                course.setStatus(ContentStatus.APPROVED);
                course.setPublishedAt(Instant.now());
            }
            var revision = courseRevisionRepository.findById(draft.revisionId()).orElseThrow();
            revision.setStatus(CourseRevisionStatus.REJECTED);
            var topic = revision.getTopics().getFirst();
            topic.setName("Revision topic title");
            topic.setOrderIndex(2);
            var linked = topic.getLessons().getFirst();
            linked.setTitle("Reimported title");
            linked.setAudioUrl("https://example.com/reimport.mp3");
            linked.setAudioPublicId("learne/course-audio/reimport");
            linked.setOrderIndex(2);
            var first = addLesson(topic, draft, "Revision-only first", 1);
            var tied = addLesson(topic, draft, "Revision-only tied", 2);
            var earlier = new TopicRevision();
            earlier.setCourseRevision(revision);
            earlier.setName("Earlier topic");
            earlier.setOrderIndex(1);
            revision.getTopics().add(earlier);
            var earlierLesson = addLesson(earlier, draft, "Earlier topic lesson", 99);
            var tiedTopic = new TopicRevision();
            tiedTopic.setCourseRevision(revision);
            tiedTopic.setName("Tied topic");
            tiedTopic.setOrderIndex(2);
            revision.getTopics().add(tiedTopic);
            var tiedTopicLesson = addLesson(tiedTopic, draft, "Tied topic lesson", 0);
            var saved = courseRevisionRepository.saveAndFlush(revision);
            var savedLessons = saved.getTopics().stream().flatMap(t -> t.getLessons().stream()).toList();
            ids[0] = savedLessons.stream().filter(l -> l.getTitle().equals(earlierLesson.getTitle())).findFirst().orElseThrow().getId();
            ids[1] = savedLessons.stream().filter(l -> l.getTitle().equals(first.getTitle())).findFirst().orElseThrow().getId();
            ids[2] = savedLessons.stream().filter(l -> l.getTitle().equals(tied.getTitle())).findFirst().orElseThrow().getId();
            ids[3] = topic.getId();
            ids[4] = savedLessons.stream().filter(l -> l.getTitle().equals(tiedTopicLesson.getTitle())).findFirst().orElseThrow().getId();
        });
        var detailBefore = detail(draft);
        discovery(draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.courseId").value(draft.courseId()))
                .andExpect(jsonPath("$.revisionId").value(draft.revisionId()))
                .andExpect(jsonPath("$.revisionStatus").value("REJECTED"))
                .andExpect(jsonPath("$.lessons.length()").value(5))
                .andExpect(jsonPath("$.lessons[0].lessonRevisionId").value(ids[0]))
                .andExpect(jsonPath("$.lessons[1].lessonRevisionId").value(ids[1]))
                .andExpect(jsonPath("$.lessons[1].sourceLessonId").isEmpty())
                .andExpect(jsonPath("$.lessons[2].lessonRevisionId").value(draft.revisionLessonId()))
                .andExpect(jsonPath("$.lessons[2].sourceLessonId").value(draft.liveLessonId()))
                .andExpect(jsonPath("$.lessons[2].topicRevisionId").value(ids[3]))
                .andExpect(jsonPath("$.lessons[2].topicTitle").value("Revision topic title"))
                .andExpect(jsonPath("$.lessons[2].title").value("Reimported title"))
                .andExpect(jsonPath("$.lessons[2].skillType").value("LISTENING"))
                .andExpect(jsonPath("$.lessons[2].orderIndex").value(2))
                .andExpect(jsonPath("$.lessons[2].audioUrl").value("https://example.com/reimport.mp3"))
                .andExpect(jsonPath("$.lessons[2].audioPublicId").value("learne/course-audio/reimport"))
                .andExpect(jsonPath("$.lessons[3].lessonRevisionId").value(ids[2]))
                .andExpect(jsonPath("$.lessons[4].lessonRevisionId").value(ids[4]));
        assertThat(detail(draft)).isEqualTo(detailBefore);
        assertThat(lessons.findById(draft.liveLessonId()).orElseThrow().getAudioUrl()).isNull();
    }

    @Test
    void emptyLatestEditableRevisionReturnsEmptyListRatherThanLiveTree() throws Exception {
        var draft = fixture.draft();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var revision = courseRevisionRepository.findById(draft.revisionId()).orElseThrow();
            revision.getTopics().clear();
        });
        discovery(draft).andExpect(status().isOk()).andExpect(jsonPath("$.lessons").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = CourseRevisionStatus.class, names = {"PENDING_REVIEW", "APPROVED", "SUPERSEDED"})
    void latestOverallNoneditableRevisionDoesNotFallBackToOlderDraft(CourseRevisionStatus state) throws Exception {
        var draft = fixture.draft();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var old = courseRevisionRepository.findById(draft.revisionId()).orElseThrow();
            var latest = new CourseRevision();
            latest.setCourse(old.getCourse());
            latest.setTeacher(old.getTeacher());
            latest.setRevisionNumber(old.getRevisionNumber() + 1);
            latest.setStatus(state);
            latest.setName(old.getName());
            latest.setType(old.getType());
            latest.setLevel(old.getLevel());
            courseRevisionRepository.save(latest);
        });
        discovery(draft).andExpect(status().isConflict());
    }

    @Test
    void ownedCourseWithoutRevisionIs409() throws Exception {
        var draft = fixture.draft();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                courseRevisionRepository.deleteById(draft.revisionId()));
        discovery(draft).andExpect(status().isConflict());
    }

    @Test
    void foreignAndMissingCoursesAre404() throws Exception {
        var owner = fixture.draft();
        var foreign = fixture.draft();
        mockMvc.perform(get("/api/teacher/content/courses/{id}/audio-targets", owner.courseId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign.accessToken()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(get("/api/teacher/content/courses/{id}/audio-targets", Long.MAX_VALUE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "lesson", "source"})
    void inconsistentForeignOwnershipNeverLeaksTargets(String part) throws Exception {
        var draft = fixture.draft();
        var foreign = fixture.draft();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var revision = courseRevisionRepository.findById(draft.revisionId()).orElseThrow();
            var lesson = revision.getTopics().getFirst().getLessons().getFirst();
            switch (part) {
                case "revision" -> revision.setTeacher(foreign.teacher());
                case "lesson" -> lesson.setTeacher(foreign.teacher());
                case "source" -> lesson.setSourceLesson(lessons.findById(foreign.liveLessonId()).orElseThrow());
                default -> throw new AssertionError(part);
            }
        });
        discovery(draft).andExpect(status().isNotFound()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void discoveryUsesIndependentReadSnapshotAndReturnsDetachedValuesWithoutCacheOrNetwork() throws Exception {
        var draft = fixture.draft();
        var method = LessonAudioReadService.class.getMethod("targets", com.learne.backend.domain.auth.User.class, Long.class);
        var attribute = new AnnotationTransactionAttributeSource().getTransactionAttribute(method, LessonAudioReadService.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.isReadOnly()).isTrue();
        assertThat(attribute.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(attribute.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        clearInvocations(storageService, clamAvClient, courseKnowledgePointClient);
        var snapshot = reads.targets(draft.teacher(), draft.courseId());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var revision = courseRevisionRepository.findById(draft.revisionId()).orElseThrow();
            revision.getTopics().getFirst().getLessons().getFirst().setTitle("New committed title");
        });
        assertThat(snapshot.lessons().getFirst().title()).isEqualTo("Morning routine");
        assertThat(reads.targets(draft.teacher(), draft.courseId()).lessons().getFirst().title())
                .isEqualTo("New committed title");
        var outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(tx -> {
            var revision = courseRevisionRepository.findById(draft.revisionId()).orElseThrow();
            revision.getTopics().getFirst().getLessons().getFirst().setTitle("Uncommitted outer title");
            assertThat(reads.targets(draft.teacher(), draft.courseId()).lessons().getFirst().title())
                    .isEqualTo("New committed title");
            tx.setRollbackOnly();
        });
        verifyNoInteractions(storageService, clamAvClient, courseKnowledgePointClient);
    }

    @Test
    void securityRequiresAuthenticatedTeacher() throws Exception {
        var draft = fixture.draft();
        mockMvc.perform(get("/api/teacher/content/courses/{id}/audio-targets", draft.courseId()))
                .andExpect(status().isUnauthorized());
        var student = userRepository.findById(draft.teacher().getId()).orElseThrow();
        student.setRole(UserRole.STUDENT);
        userRepository.saveAndFlush(student);
        discovery(draft).andExpect(status().isForbidden());
    }

    @Test
    void discoveryPreservesCourseDetailIdsAndJsonEditDeleteTarget() throws Exception {
        var draft = fixture.draft();
        var unrelated = fixture.draft();
        JsonNode before = detail(draft);
        long detailLessonId = before.at("/topics/0/lessons/0/id").asLong();
        assertThat(detailLessonId).isEqualTo(draft.liveLessonId());
        discovery(draft).andExpect(status().isOk());
        assertThat(detail(draft)).isEqualTo(before);
        mockMvc.perform(put("/api/teacher/content/lessons/{id}", detailLessonId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Edited intended live lesson","description":"Fixture",
                                 "content":"Updated", "skillType":"LISTENING","orderIndex":1,
                                 "xpReward":10,"placementTest":false}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(detailLessonId));
        assertThat(lessons.findById(draft.liveLessonId()).orElseThrow().getTitle())
                .isEqualTo("Edited intended live lesson");
        assertThat(lessons.findById(unrelated.liveLessonId()).orElseThrow().getTitle()).isEqualTo("Morning routine");
        discovery(draft).andExpect(status().isOk());
        long afterId = detail(draft).at("/topics/0/lessons/0/id").asLong();
        assertThat(afterId).isEqualTo(detailLessonId);
        mockMvc.perform(delete("/api/teacher/content/lessons/{id}", afterId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isOk());
        assertThat(lessons.existsById(draft.liveLessonId())).isFalse();
        assertThat(lessons.existsById(unrelated.liveLessonId())).isTrue();
    }

    private LessonRevision addLesson(TopicRevision topic, LessonAudioTestFixture.Draft draft,
                                     String title, int order) {
        var lesson = new LessonRevision();
        lesson.setTopicRevision(topic);
        lesson.setTeacher(draft.teacher());
        lesson.setTitle(title);
        lesson.setSkillType(SkillType.READING);
        lesson.setOrderIndex(order);
        topic.getLessons().add(lesson);
        return lesson;
    }

    static Stream<Arguments> audioKindsAndSkills() {
        return Stream.of(false, true).flatMap(revision -> Stream.of(SkillType.values())
                .map(skill -> Arguments.of(revision, skill)));
    }

    @ParameterizedTest
    @MethodSource("audioKindsAndSkills")
    void uploadReplaceAndRepeatedDeleteMirrorBothNamespaces(boolean revision, SkillType skill) throws Exception {
        var draft = fixture.draft();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            lessons.findById(draft.liveLessonId()).orElseThrow().setSkillType(skill);
            revisionLesson(draft).setSkillType(skill);
            if (revision) {
                courses.findById(draft.courseId()).orElseThrow().setStatus(ContentStatus.REJECTED);
                lessons.findById(draft.liveLessonId()).orElseThrow().setStatus(ContentStatus.REJECTED);
                courseRevisionRepository.findById(draft.revisionId()).orElseThrow().setStatus(CourseRevisionStatus.REJECTED);
            }
        });
        when(storageService.uploadLessonAudio(any())).thenReturn(
                new StorageUploadResult("https://example.com/first.mp3", "learne/course-audio/first"),
                new StorageUploadResult("https://example.com/second.mp3", "learne/course-audio/second"));
        String uri = audioUri(draft, revision);
        upload(draft, uri, validFile()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(revision ? draft.revisionLessonId() : draft.liveLessonId()))
                .andExpect(jsonPath("$.skillType").value(skill.name()))
                .andExpect(jsonPath("$.status").value(revision ? "REJECTED" : "DRAFT"))
                .andExpect(jsonPath("$.audioPublicId").value("learne/course-audio/first"));
        upload(draft, uri, validFile()).andExpect(status().isOk())
                .andExpect(jsonPath("$.audioUrl").value("https://example.com/second.mp3"));
        assertAudio(draft, "https://example.com/second.mp3");
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.audioUrl").isEmpty())
                    .andExpect(jsonPath("$.audioPublicId").isEmpty());
            assertAudio(draft, null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preflightRejectsForeignMissingAndNoneditableBeforeFileValidation(boolean revision) throws Exception {
        var draft = fixture.draft();
        var foreign = fixture.draft();
        clearInvocations(storageService, clamAvClient);
        upload(foreign, audioUri(draft, revision), validFile()).andExpect(status().isNotFound());
        mockMvc.perform(delete(audioUri(draft, revision)).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + foreign.accessToken())).andExpect(status().isNotFound());
        upload(draft, "/api/teacher/content/" + (revision ? "lesson-revisions/" : "lessons/")
                + Long.MAX_VALUE + "/audio", validFile()).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/teacher/content/" + (revision ? "lesson-revisions/" : "lessons/")
                        + Long.MAX_VALUE + "/audio").header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isNotFound());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                courseRevisionRepository.findById(draft.revisionId()).orElseThrow()
                        .setStatus(CourseRevisionStatus.PENDING_REVIEW));
        upload(draft, audioUri(draft, revision), new MockMultipartFile("file", new byte[0]))
                .andExpect(status().isConflict());
        mockMvc.perform(delete(audioUri(draft, revision)).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + draft.accessToken())).andExpect(status().isConflict());
        verifyNoInteractions(storageService, clamAvClient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void uploadValidationAndMediaErrorsUseMessageEnvelope(boolean revision) throws Exception {
        var draft = fixture.draft();
        String uri = audioUri(draft, revision);
        clearInvocations(storageService, clamAvClient);
        for (var file : new MockMultipartFile[]{new MockMultipartFile("file", new byte[0]),
                new MockMultipartFile("file", "bad.mp3", "audio/mpeg", "bad".getBytes()),
                new MockMultipartFile("file", "bad.mp3", null, "ID3".getBytes()),
                new MockMultipartFile("file", "bad.txt", "text/plain", "ID3".getBytes())}) {
            upload(draft, uri, file).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        }
        mockMvc.perform(multipart(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.message").isNotEmpty());
        upload(draft, uri, new MockMultipartFile("file", "big.mp3", "audio/mpeg", new byte[20 * 1024 * 1024 + 1]))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.message").isNotEmpty());
        verifyNoInteractions(storageService, clamAvClient);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scannerAndProviderFailuresKeepDatabaseUnchanged(boolean revision) throws Exception {
        var draft = fixture.draft();
        for (var state : new HttpStatus[]{HttpStatus.BAD_REQUEST, HttpStatus.BAD_GATEWAY}) {
            doThrow(new ApiException(state, "scanner")).when(clamAvClient).assertClean(anyString(), any());
            upload(draft, audioUri(draft, revision), validFile()).andExpect(status().is(state.value()))
                    .andExpect(jsonPath("$.message").value("scanner"));
        }
        verify(storageService, never()).uploadLessonAudio(any());
        doNothing().when(clamAvClient).assertClean(anyString(), any());
        for (var state : new HttpStatus[]{HttpStatus.BAD_GATEWAY, HttpStatus.INTERNAL_SERVER_ERROR}) {
            doThrow(new ApiException(state, "provider")).when(storageService).uploadLessonAudio(any());
            upload(draft, audioUri(draft, revision), validFile()).andExpect(status().is(state.value()))
                    .andExpect(jsonPath("$.message").value("provider"));
        }
        assertAudio(draft, null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactApplicationLimitCommitsDespitePostCommitCacheFailure(boolean revision) throws Exception {
        var draft = fixture.draft();
        byte[] bytes = new byte[20 * 1024 * 1024];
        bytes[0] = 'I'; bytes[1] = 'D'; bytes[2] = '3';
        var uploaded = managedUpload("retained");
        when(storageService.uploadLessonAudio(any())).thenReturn(uploaded);
        doThrow(new IllegalStateException("cache unavailable")).when(cache).evictContentAfterAudioCommit(any());
        upload(draft, audioUri(draft, revision), new MockMultipartFile("file", "audio.mp3", "audio/mpeg", bytes))
                .andExpect(status().isOk());
        assertAudio(draft, uploaded.url());
        verify(storageService, never()).delete(any(), any());
    }

    @Test
    void publishedRevisionPreservesLiveAndUnpublishedRevisionRepairsMismatch() throws Exception {
        var draft = fixture.draft();
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult(
                "https://example.com/new.mp3", "learne/course-audio/new"));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            revisionLesson(draft).setAudioUrl("mismatch");
        });
        upload(draft, audioUri(draft, false), validFile()).andExpect(status().isConflict());
        verify(storageService, never()).uploadLessonAudio(any());
        verifyNoInteractions(clamAvClient);
        upload(draft, audioUri(draft, true), validFile()).andExpect(status().isOk());
        assertAudio(draft, "https://example.com/new.mp3");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            courses.findById(draft.courseId()).orElseThrow().setPublishedAt(Instant.now());
            revisionLesson(draft).setAudioUrl("revision-only-before");
        });
        upload(draft, audioUri(draft, false), validFile()).andExpect(status().isConflict());
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult(
                "https://example.com/published-revision.mp3", "learne/course-audio/published-revision"));
        upload(draft, audioUri(draft, true), validFile()).andExpect(status().isOk());
        assertThat(lessons.findById(draft.liveLessonId()).orElseThrow().getAudioUrl()).isEqualTo("https://example.com/new.mp3");
        mockMvc.perform(delete(audioUri(draft, false)).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isConflict());
        mockMvc.perform(delete(audioUri(draft, true)).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.audioUrl").isEmpty());
        assertThat(lessons.findById(draft.liveLessonId()).orElseThrow().getAudioUrl()).isEqualTo("https://example.com/new.mp3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mutationSecurityRequiresTeacher(boolean revision) throws Exception {
        var draft = fixture.draft();
        String uri = audioUri(draft, revision);
        mockMvc.perform(multipart(uri).file(validFile())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(uri)).andExpect(status().isUnauthorized());
        var user = userRepository.findById(draft.teacher().getId()).orElseThrow();
        user.setRole(UserRole.STUDENT);
        userRepository.saveAndFlush(user);
        upload(draft, uri, validFile()).andExpect(status().isForbidden());
        mockMvc.perform(delete(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isForbidden());
    }

    private LessonRevision revisionLesson(LessonAudioTestFixture.Draft draft) {
        return courseRevisionRepository.findById(draft.revisionId()).orElseThrow().getTopics().getFirst().getLessons().getFirst();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleStateDuringScannerReturns409AndCompensatesOnlyNewAsset(boolean revision) throws Exception {
        var draft = fixture.draft();
        var uploaded = managedUpload("stale-new");
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult(uploaded.url(), uploaded.publicId()));
        doAnswer(invocation -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    courseRevisionRepository.findById(draft.revisionId()).orElseThrow()
                            .setStatus(CourseRevisionStatus.PENDING_REVIEW));
            return null;
        }).when(clamAvClient).assertClean(anyString(), any());
        upload(draft, audioUri(draft, revision), validFile()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").isNotEmpty());
        assertAudio(draft, null);
        verify(storageService).delete(uploaded.publicId(), StorageResourceType.VIDEO);
    }

    @Test
    void unknownDatabaseFailurePropagatesWithout400OrAssetCompensation() throws Exception {
        var draft = fixture.draft();
        var uploaded = managedUpload("unknown-new");
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult(uploaded.url(), uploaded.publicId()));
        doThrow(new DataAccessResourceFailureException("unknown transaction outcome")).when(audioPersistence)
                .replace(any(), any(), any(), any());
        assertThatThrownBy(() -> upload(draft, audioUri(draft, true), validFile()))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);
        assertAudio(draft, null);
        verify(storageService, never()).delete(any(), any());
    }

    @Test
    void unexpectedPostCommitFailureRetainsCommittedAssetAndIsNotConvertedTo400() throws Exception {
        var draft = fixture.draft();
        var uploaded = managedUpload("committed-new");
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult(uploaded.url(), uploaded.publicId()));
        doThrow(new IllegalStateException("post-commit failure")).when(audioPostCommit).complete(any(), any(), any());
        assertThatThrownBy(() -> upload(draft, audioUri(draft, true), validFile()))
                .hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("post-commit failure");
        assertAudio(draft, uploaded.url());
        verify(storageService, never()).delete(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void historicRevisionNeverFallsBackToLatestForMutation(boolean revision) throws Exception {
        var draft = fixture.draft();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var old = courseRevisionRepository.findById(draft.revisionId()).orElseThrow();
            var latest = new CourseRevision();
            latest.setCourse(old.getCourse()); latest.setTeacher(old.getTeacher());
            latest.setRevisionNumber(old.getRevisionNumber() + 1); latest.setStatus(CourseRevisionStatus.DRAFT);
            latest.setName(old.getName()); latest.setType(old.getType()); latest.setLevel(old.getLevel());
            courseRevisionRepository.saveAndFlush(latest);
        });
        clearInvocations(storageService, clamAvClient);
        upload(draft, audioUri(draft, revision), validFile()).andExpect(status().isConflict());
        mockMvc.perform(delete(audioUri(draft, revision)).header(HttpHeaders.AUTHORIZATION,
                "Bearer " + draft.accessToken())).andExpect(status().isConflict());
        verifyNoInteractions(storageService, clamAvClient);
    }

    @Test
    void sourceLessRevisionWithNumericLiveCollisionOnlyChangesRevisionNamespace() throws Exception {
        var draft = fixture.draft();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> revisionLesson(draft).setSourceLesson(null));
        // Explicit collision: identity counters intentionally survive the database cleaner.
        if (draft.liveLessonId() != draft.revisionLessonId()) {
            jdbcTemplate.update("""
                    insert into lessons (id, topic_id, teacher_id, title, skill_type, order_index,
                        xp_reward, is_locked, is_placement_test, status, created_at, updated_at)
                    select ?, topic_id, teacher_id, title, skill_type, order_index,
                        xp_reward, is_locked, is_placement_test, status, created_at, updated_at
                    from lessons where id = ?
                    """, draft.revisionLessonId(), draft.liveLessonId());
        }
        var uploaded = managedUpload("revision-only");
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult(uploaded.url(), uploaded.publicId()));
        upload(draft, audioUri(draft, true), validFile()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(draft.revisionLessonId()))
                .andExpect(jsonPath("$.audioUrl").value(uploaded.url()));
        assertThat(lessons.findById(draft.revisionLessonId()).orElseThrow().getAudioUrl()).isNull();
        mockMvc.perform(delete(audioUri(draft, true)).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.audioUrl").isEmpty());
        assertThat(lessons.findById(draft.revisionLessonId()).orElseThrow().getAudioPublicId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mappedMutationIgnoresUnrelatedNumericCollisionAndChangesCorrectMirror(boolean revision) throws Exception {
        var draft = fixture.draft();
        var unrelated = fixture.draft();
        long targetId = revision ? draft.revisionLessonId() : draft.liveLessonId();
        long collisionId = 900_000L + targetId;
        if (revision) {
            jdbcTemplate.update("""
                    insert into lessons (id, topic_id, teacher_id, title, skill_type, order_index,
                        xp_reward, is_locked, is_placement_test, status, created_at, updated_at)
                    select ?, topic_id, teacher_id, title, skill_type, order_index,
                        xp_reward, is_locked, is_placement_test, status, created_at, updated_at
                    from lessons where id = ?
                    """, collisionId, unrelated.liveLessonId());
            // Move the requested revision ID into a disjoint, explicit collision namespace.
            jdbcTemplate.update("update lesson_revisions set id = ? where id = ?", collisionId, targetId);
        } else {
            jdbcTemplate.update("""
                    insert into lesson_revisions (id, topic_revision_id, teacher_id, title, skill_type,
                        order_index, xp_reward, is_placement_test)
                    select ?, topic_revision_id, teacher_id, title, skill_type,
                        order_index, xp_reward, is_placement_test
                    from lesson_revisions where id = ?
                    """, collisionId, unrelated.revisionLessonId());
            jdbcTemplate.update("update lesson_revisions set source_lesson_id = null where source_lesson_id = ?", targetId);
            jdbcTemplate.update("update lessons set id = ? where id = ?", collisionId, targetId);
            jdbcTemplate.update("update lesson_revisions set source_lesson_id = ? where id = ?", collisionId, draft.revisionLessonId());
        }
        var uploaded = managedUpload("collision");
        when(storageService.uploadLessonAudio(any())).thenReturn(new StorageUploadResult(uploaded.url(), uploaded.publicId()));
        String uri = "/api/teacher/content/" + (revision ? "lesson-revisions/" : "lessons/") + collisionId + "/audio";
        upload(draft, uri, validFile()).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(collisionId));
        assertThat(lessons.findById(revision ? draft.liveLessonId() : collisionId).orElseThrow().getAudioUrl())
                .isEqualTo(uploaded.url());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(revisionLesson(draft).getAudioUrl()).isEqualTo(uploaded.url()));
        if (revision) {
            assertThat(lessons.findById(collisionId).orElseThrow().getAudioUrl()).isNull();
        } else {
            assertThat(jdbcTemplate.queryForObject("select audio_url from lesson_revisions where id = ?",
                    String.class, collisionId)).isNull();
        }
        assertAudio(unrelated, null);
    }

    @Test
    void unrelatedProfileAndImportMultipartErrorsKeepFrameworkStatusAndEmptyBody() throws Exception {
        var draft = fixture.draft();
        for (String uri : new String[]{"/api/teacher/profile/avatar", "/api/teacher/content/import"}) {
            mockMvc.perform(multipart(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(""));
            mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content("{}")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(content().string(""));
            mockMvc.perform(multipart(uri)).andExpect(status().isUnauthorized())
                    .andExpect(content().string(""));
        }
    }

    private void assertAudio(LessonAudioTestFixture.Draft draft, String url) {
        var live = lessons.findById(draft.liveLessonId()).orElseThrow();
        assertThat(live.getAudioUrl()).isEqualTo(url);
        if (url == null) assertThat(live.getAudioPublicId()).isNull();
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var revision = revisionLesson(draft);
            assertThat(revision.getAudioUrl()).isEqualTo(url);
            assertThat(revision.getAudioPublicId()).isEqualTo(live.getAudioPublicId());
        });
    }

    private StorageUploadResult managedUpload(String suffix) {
        return new StorageUploadResult("https://res.cloudinary.com/test-cloud/video/upload/v123/learne/course-audio/"
                + suffix + ".mp3", "learne/course-audio/" + suffix);
    }

    private String audioUri(LessonAudioTestFixture.Draft draft, boolean revision) {
        return "/api/teacher/content/" + (revision ? "lesson-revisions/" + draft.revisionLessonId()
                : "lessons/" + draft.liveLessonId()) + "/audio";
    }

    private MockMultipartFile validFile() {
        return new MockMultipartFile("file", "audio.mp3", "audio/mpeg", "ID3audio".getBytes());
    }

    private ResultActions upload(LessonAudioTestFixture.Draft draft, String uri, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart(uri).file(file).header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()));
    }

    private ResultActions discovery(LessonAudioTestFixture.Draft draft) throws Exception {
        return mockMvc.perform(get("/api/teacher/content/courses/{id}/audio-targets", draft.courseId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()));
    }

    private JsonNode detail(LessonAudioTestFixture.Draft draft) throws Exception {
        var result = mockMvc.perform(get("/api/teacher/content/courses/{id}", draft.courseId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + draft.accessToken()))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
