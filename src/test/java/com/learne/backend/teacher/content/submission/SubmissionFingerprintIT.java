package com.learne.backend.teacher.content.submission;

import com.learne.backend.jobs.JobsPostgresTestSupport;
import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SubmissionFingerprintIT extends JobsPostgresTestSupport {
    @Autowired SubmissionProjectionReader reader;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    private long teacher, course, revision, topic, lesson;
    private void fixture(int count) {
        teacher = jdbc.queryForObject("insert into users(email,password_hash,full_name,role) values ('projection@test.test','test','Teacher','TEACHER') returning id", Long.class);
        course = jdbc.queryForObject("insert into courses(teacher_id,name,type,level,status) values (?,'Course','GENERAL','A1','DRAFT') returning id", Long.class,teacher);
        revision = jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,1,'DRAFT','Course','GENERAL','A1') returning id", Long.class,course,teacher);
        topic = jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Topic') returning id",Long.class,revision);
        lesson = jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,content) values (?,?,'Lesson','READING',?) returning id",Long.class,topic,teacher,"文".repeat(2500));
        jdbc.update("insert into question_revisions(lesson_revision_id,teacher_id,type,question_text,order_index) select ?,?,'MULTIPLE_CHOICE','Question '||n,n from generate_series(0,?-1) n order by n desc",lesson,teacher,count);
        jdbc.update("insert into question_option_revisions(question_revision_id,option_text) select id,'Option' from question_revisions where lesson_revision_id=?",lesson);
    }
    private SubmissionProjectionReader.Capture capture(int size) { return reader.capture(course,EnrichmentContract.V1,size); }
    @SuppressWarnings("unchecked")
    private JdbcTemplate countingJdbc(List<Integer> rows) {
        var observed=spy(new JdbcTemplate(dataSource));
        doAnswer(invocation -> {
            List<?> result=(List<?>)invocation.callRealMethod(); rows.add(result.size()); return result;
        }).when(observed).query(anyString(),any(RowMapper.class),any(Object[].class));
        doAnswer(invocation -> {
            RowCallbackHandler consumer=invocation.getArgument(2);
            int[] count={0};
            invocation.getArguments()[2]=(RowCallbackHandler)rs -> {
                assertThat(rs.getType()).isEqualTo(java.sql.ResultSet.TYPE_FORWARD_ONLY);
                assertThat(rs.getFetchSize()).isBetween(1,100);
                assertThat(rs.getStatement().getConnection().getAutoCommit()).isFalse();
                var nativeResult=rs.unwrap(org.postgresql.jdbc.PgResultSet.class);
                var buffered=(List<?>)org.springframework.test.util.ReflectionTestUtils.getField(nativeResult,"rows");
                assertThat(buffered).hasSizeLessThanOrEqualTo(rs.getFetchSize());
                count[0]++; consumer.processRow(rs);
            };
            invocation.callRealMethod(); rows.add(count[0]); return null;
        }).when(observed).query(anyString(),any(PreparedStatementSetter.class),any(RowCallbackHandler.class));
        return observed;
    }
    @Test void substantialOptionsUseOneIncrementalCursorForCaptureAndTargetedBatch() {
        fixture(1);
        long question=jdbc.queryForObject("select id from question_revisions",Long.class);
        jdbc.update("insert into question_option_revisions(question_revision_id,option_text,order_index) select ?,repeat('x',1000),n from generate_series(1,20000) n",question);
        var rows=new ArrayList<Integer>();
        var observed=countingJdbc(rows);
        var projection=new SubmissionProjectionReader(observed);
        var tx=new TransactionTemplate(manager); tx.setReadOnly(true);
        var captured=tx.execute(status -> projection.capture(course,EnrichmentContract.V1,37));
        assertThat(rows).containsExactly(1,20004);
        assertThat(mockingDetails(observed).getInvocations().stream().filter(i -> i.getMethod().getName().equals("query")
                && i.getMethod().getParameterCount()==3 && i.getMethod().getParameterTypes()[2]==RowCallbackHandler.class)).hasSize(1);
        rows.clear();
        var batch=tx.execute(status -> projection.batchContext(revision,List.of(question),EnrichmentContract.V1));
        assertThat(rows).containsExactly(1,20004);
        assertThat(batch.request().questions().getFirst().options()).hasSize(8);
        assertThat(batch.questions().getFirst().optionTexts()).hasSize(8);
        assertThat(batch.questions().getFirst().inputFingerprint()).isEqualTo(captured.manifestEntries().getFirst().perQuestionInputFingerprint());
    }
    @Test void rejectsOversizedRevisionsAndInvalidFetchSizes() {
        fixture(1001);
        assertThatThrownBy(() -> capture(100)).isInstanceOf(IllegalArgumentException.class).hasMessage("Revision exceeds question limit");
        for(int size:new int[]{-1,0,101,Integer.MAX_VALUE})
            assertThatThrownBy(() -> capture(size)).isInstanceOf(IllegalArgumentException.class).hasMessage("Page size must be between 1 and 100");
    }
    @Test void targetedBatchMaterializesOnlyRequestedNodesAndTheirAncestors() {
        fixture(1000);
        jdbc.update("insert into topic_revisions(revision_id,name) values (?,'Unrelated empty')",revision);
        var full=capture(100);
        var rows=new ArrayList<Integer>();
        var observed=countingJdbc(rows);
        var projection=new SubmissionProjectionReader(observed);
        var tx=new TransactionTemplate(manager);
        tx.setReadOnly(true); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        for(int count:new int[]{2,25}) {
            rows.clear();
            clearInvocations(observed);
            var selected=full.manifestEntries().subList(500,500+count);
            var ids=selected.stream().map(SubmissionProjectionReader.ManifestEntry::questionRevisionId).toList();
            var batch=tx.execute(status -> projection.batchContext(revision,ids,EnrichmentContract.V1));
            assertThat(rows).containsExactly(1,2+2*count);
            var statements=mockingDetails(observed).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("query") && i.getMethod().getParameterCount()==3
                            && (i.getMethod().getParameterTypes()[1]==RowMapper.class
                            || i.getMethod().getParameterTypes()[2]==RowCallbackHandler.class))
                    .map(i -> ((String)i.getArgument(0)).toLowerCase(Locale.ROOT)).toList();
            assertThat(statements).hasSize(2);
            assertThat(statements.getFirst()).contains("from course_revisions r", "where r.id=?")
                    .doesNotContain("count(","question_revisions","lesson_revisions","topic_revisions");
            assertThat(statements.get(1)).contains("where t.revision_id=? and q.id in (", "order by path")
                    .doesNotContain("count(","limit ","offset ");
            assertThat(batch.questions()).extracting(SubmissionProjectionReader.QuestionContext::inputFingerprint)
                    .containsExactlyElementsOf(selected.stream().map(SubmissionProjectionReader.ManifestEntry::perQuestionInputFingerprint).toList());
        }
        long id=full.manifestEntries().getFirst().questionRevisionId();
        assertThatThrownBy(() -> reader.batchContext(revision,List.of(id,id),EnrichmentContract.V1)).isInstanceOf(IllegalArgumentException.class);
        long secondTopic=jdbc.queryForObject("insert into topic_revisions(revision_id,name,description) values (?,'Selected other topic','Other context') returning id",Long.class,revision);
        long secondLesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type,content) values (?,?,'Selected other lesson','LISTENING','Other content') returning id",Long.class,secondTopic,teacher);
        long last=full.manifestEntries().getLast().questionRevisionId();
        jdbc.update("update question_revisions set lesson_revision_id=? where id=?",secondLesson,last);
        var differentParents=capture(100);
        var beforeBatch=fixtureSnapshot();
        rows.clear();
        var crossParent=tx.execute(status -> projection.batchContext(revision,List.of(last,id),EnrichmentContract.V1));
        assertThat(rows).containsExactly(1,8);
        assertThat(crossParent.questions()).extracting(SubmissionProjectionReader.QuestionContext::inputFingerprint)
                .containsExactlyElementsOf(differentParents.manifestEntries().stream().filter(q -> q.questionRevisionId()==id || q.questionRevisionId()==last)
                        .map(SubmissionProjectionReader.ManifestEntry::perQuestionInputFingerprint).toList());
        assertThat(crossParent.request().questions()).extracting(com.learne.backend.teacher.content.enrichment.CourseKnowledgePointRequest.QuestionPayload::topicId)
                .containsExactly(topic,secondTopic);
        assertThat(fixtureSnapshot()).isEqualTo(beforeBatch);
        long otherRevision=jdbc.queryForObject("insert into course_revisions(course_id,teacher_id,revision_number,status,name,type,level) values (?,?,2,'DRAFT','Other','GENERAL','A1') returning id",Long.class,course,teacher);
        assertThatThrownBy(() -> reader.batchContext(otherRevision,List.of(id),EnrichmentContract.V1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(capture(100).header().revisionId()).isEqualTo(otherRevision);
    }
    @Test void thousandQuestionsStableAcrossPagesAndBatchIsBoundedAndImmutable() {
        fixture(1000);
        var a = capture(2); var b = capture(100);
        assertThat(a).isEqualTo(b);
        assertThat(a.header().questionCount()).isEqualTo(1000);
        assertThat(reader.latestHeader(course).orElseThrow().questionCount()).isEqualTo(1000);
        assertThat(a.manifestEntries()).hasSize(1000);
        for (int i=0;i<1000;i++) assertThat(a.manifestEntries().get(i).ordinal()).isEqualTo(i);
        var ids = a.manifestEntries().subList(0,25).stream().map(SubmissionProjectionReader.ManifestEntry::questionRevisionId).toList();
        var batch = reader.batchContext(revision,ids,EnrichmentContract.V1);
        assertThat(batch.request().questions()).hasSize(25);
        assertThat(batch.request().questions().getFirst().lessonContent()).hasSize(2000);
        assertThat(batch.questions().getFirst().lessonContent()).hasSize(2500);
        assertThat(batch.questions().getFirst().inputFingerprint()).isEqualTo(a.manifestEntries().getFirst().perQuestionInputFingerprint());
        assertThat(capture(100)).isEqualTo(a);
        assertThatThrownBy(() -> a.manifestEntries().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> reader.batchContext(revision,a.manifestEntries().subList(0,26).stream().map(SubmissionProjectionReader.ManifestEntry::questionRevisionId).toList(),EnrichmentContract.V1)).isInstanceOf(IllegalArgumentException.class);
    }
    private Map<String,List<Map<String,Object>>> fixtureSnapshot() {
        var snapshot=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("users","courses","course_revisions","topics","lessons","questions","question_options",
                "topic_revisions","lesson_revisions","question_revisions","question_option_revisions"))
            snapshot.put(table,jdbc.queryForList("select * from "+table+" order by id"));
        return snapshot;
    }
    @Test void allPayloadFieldsAreBoundedWhileFullInputsAndEveryOptionRemainHashed() {
        fixture(1);
        long question=capture(100).manifestEntries().getFirst().questionRevisionId();
        jdbc.update("update course_revisions set name=?,description=?,level=?","n".repeat(255),"d".repeat(1001),"L".repeat(33));
        jdbc.update("update topic_revisions set name=?,description=?","t".repeat(255),"d".repeat(1001));
        jdbc.update("update lesson_revisions set title=?,description=?,content=?","l".repeat(255),"d".repeat(1001),"c".repeat(2001));
        jdbc.update("update question_revisions set question_text=?,correct_answer=?","q".repeat(1001),"a".repeat(501));
        jdbc.update("delete from question_option_revisions");
        jdbc.update("insert into question_option_revisions(question_revision_id,option_text,order_index) values (?,'   ',-1)",question);
        jdbc.update("insert into question_option_revisions(question_revision_id,option_text,order_index) select ?,?,n from generate_series(0,139) n",question,"o".repeat(251));
        var before=fixtureSnapshot();
        var full=capture(100);
        var rows=new ArrayList<Integer>();
        var projection=new SubmissionProjectionReader(countingJdbc(rows));
        var tx=new TransactionTemplate(manager); tx.setReadOnly(true);
        var batch=tx.execute(status -> projection.batchContext(revision,List.of(question),EnrichmentContract.V1));
        assertThat(rows).containsExactly(1,144);
        var payload=batch.request().questions().getFirst();
        assertThat(batch.request().course().name()).hasSize(255);
        assertThat(batch.request().course().description()).hasSize(1000);
        assertThat(batch.request().course().level()).hasSize(32);
        assertThat(payload.topicName()).hasSize(255); assertThat(payload.topicDescription()).hasSize(1000);
        assertThat(payload.lessonTitle()).hasSize(255); assertThat(payload.lessonDescription()).hasSize(1000);
        assertThat(payload.lessonContent()).hasSize(2000); assertThat(payload.questionText()).hasSize(1000);
        assertThat(payload.correctAnswer()).hasSize(500); assertThat(payload.options()).hasSize(8);
        assertThat(payload.options()).allSatisfy(option -> assertThat(option.optionText()).hasSize(250));
        var context=batch.questions().getFirst();
        assertThat(context.lessonContent()).hasSize(2001); assertThat(context.questionText()).hasSize(1001);
        assertThat(context.correctAnswer()).hasSize(501); assertThat(context.courseLevel()).hasSize(33);
        assertThat(context.optionTexts()).hasSize(8).allSatisfy(text -> assertThat(text).hasSize(251));
        var calculator=new com.learne.backend.teacher.content.enrichment.QuestionDifficultyCalculator();
        var skill=com.learne.backend.teacher.content.enums.SkillType.READING;
        var type=com.learne.backend.teacher.content.enums.QuestionType.MULTIPLE_CHOICE;
        assertThat(calculator.calculate(context.courseLevel(),skill,context.lessonContent(),type,context.questionText(),context.correctAnswer(),context.optionTexts()))
                .isEqualTo(calculator.calculate("L".repeat(33),skill,"c".repeat(2001),type,"q".repeat(1001),"a".repeat(501),
                        jdbc.queryForList("select option_text from question_option_revisions order by order_index,id",String.class)));
        assertThat(fixtureSnapshot()).isEqualTo(before);
        for(String sql:List.of("update lesson_revisions set content=content||'tail'",
                "update question_revisions set question_text=question_text||'tail'",
                "update question_option_revisions set option_text='changed outside payload',is_correct=true where order_index=139")) {
            var previous=capture(100);
            jdbc.update(sql);
            var changed=reader.batchContext(revision,List.of(question),EnrichmentContract.V1);
            assertThat(changed.request()).isEqualTo(batch.request());
            assertThat(changed.questions().getFirst().inputFingerprint()).isNotEqualTo(previous.manifestEntries().getFirst().perQuestionInputFingerprint());
            assertThat(capture(100).globalFingerprint()).isNotEqualTo(previous.globalFingerprint());
            assertThat(changed.questions().getFirst().inputFingerprint()).isEqualTo(capture(100).manifestEntries().getFirst().perQuestionInputFingerprint());
        }
        assertThat(full.manifestEntries().getFirst().perQuestionInputFingerprint()).isEqualTo(context.inputFingerprint());
    }

    @Test void fixedIdPhysicalReinsertionWithEqualOrderTiesIsDeterministic() {
        fixture(4);
        jdbc.update("insert into topic_revisions(revision_id,name) values (?,'Second')",revision);
        jdbc.update("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type) values (?,?,'Second','READING')",topic,teacher);
        jdbc.update("insert into question_option_revisions(question_revision_id,option_text) select id,'Second' from question_revisions");
        jdbc.update("update question_revisions set order_index=0");
        var original=capture(2);
        var before=fixtureSnapshot();
        jdbc.update("delete from course_revisions where id=?",revision);
        for(String table:List.of("course_revisions","topic_revisions","lesson_revisions","question_revisions","question_option_revisions")) {
            var reversed=new ArrayList<>(before.get(table)); Collections.reverse(reversed);
            for(var row:reversed) {
                String columns=String.join(",",row.keySet());
                String binds=String.join(",",Collections.nCopies(row.size(),"?"));
                jdbc.update("insert into "+table+" ("+columns+") values ("+binds+")",row.values().toArray());
            }
        }
        assertThat(capture(100)).isEqualTo(original); assertThat(capture(2)).isEqualTo(original);
        assertThat(fixtureSnapshot()).isEqualTo(before);
        assertThat(original.manifestEntries()).extracting(SubmissionProjectionReader.ManifestEntry::questionRevisionId).isSorted();
    }

    @Test void captureJoinsOwnerWriteTransactionWithoutChangingIsolationOrReadOnlyAndRollsBack() {
        fixture(2);
        var before=fixtureSnapshot(); var original=capture(100);
        var tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.executeWithoutResult(status -> {
            var pid=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
            jdbc.update("update course_revisions set description='uncommitted owner edit' where id=?",revision);
            assertThat(capture(2).header().description()).isEqualTo("uncommitted owner edit");
            assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("read committed");
            assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("off");
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(jdbc.queryForObject("select pg_backend_pid()",Integer.class)).isEqualTo(pid);
            status.setRollbackOnly();
        });
        assertThat(capture(100)).isEqualTo(original); assertThat(fixtureSnapshot()).isEqualTo(before);
    }

    @Test void standaloneCaptureKeepsRepeatableReadSnapshotAcrossConcurrentMutationBetweenFetches() throws Exception {
        fixture(3);
        var original=capture(2);
        var observed=spy(new JdbcTemplate(dataSource));
        var firstPage=new CountDownLatch(1); var edited=new CountDownLatch(1); var once=new AtomicBoolean();
        doAnswer(invocation -> {
            RowCallbackHandler consumer=invocation.getArgument(2);
            int[] consumed={0};
            invocation.getArguments()[2]=(RowCallbackHandler)rs -> {
                consumer.processRow(rs);
                if(++consumed[0]==2 && once.compareAndSet(false,true)) {
                    assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("repeatable read");
                    assertThat(jdbc.queryForObject("show transaction_read_only",String.class)).isEqualTo("on");
                    firstPage.countDown();
                    try { assertThat(edited.await(5,TimeUnit.SECONDS)).isTrue(); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                }
            };
            invocation.callRealMethod(); return null;
        }).when(observed).query(anyString(),any(PreparedStatementSetter.class),any(RowCallbackHandler.class));
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(reader);
        org.springframework.test.util.ReflectionTestUtils.setField(target,"jdbc",observed);
        var pool=Executors.newSingleThreadExecutor();
        try {
            var writer=pool.submit(() -> {
                try { assertThat(firstPage.await(5,TimeUnit.SECONDS)).isTrue();
                    jdbc.update("update lesson_revisions set content='concurrent changed content' where id=?",lesson);
                    jdbc.update("update question_revisions set explanation='concurrent explanation'");
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                finally { edited.countDown(); }
            });
            assertThat(capture(2)).isEqualTo(original);
            writer.get(5,TimeUnit.SECONDS);
        } finally {
            edited.countDown(); pool.shutdownNow();
            org.springframework.test.util.ReflectionTestUtils.setField(target,"jdbc",jdbc);
        }
        assertThat(capture(2).globalFingerprint()).isNotEqualTo(original.globalFingerprint());
    }
    @Test void everyStoredFieldGroupAndEmptyParentsAffectGlobalButSiblingDoesNotAliasQuestionHash() {
        fixture(2);
        String[][] mutations = {
            {"course_revisions","name='Changed'"},{"course_revisions","description='Description'"},
            {"course_revisions","pricing_type='PAID'"},{"course_revisions","proposed_price_amount_vnd=100"},
            {"course_revisions","revision_number=2"},{"course_revisions","type='EXAM'"},
            {"course_revisions","level='B2'"},{"course_revisions","order_index=2"},
            {"topic_revisions","name='Renamed'"},
            {"topic_revisions","description='Context'"},{"topic_revisions","order_index=4"},
            {"lesson_revisions","title='Renamed'"},{"lesson_revisions","description='Description'"},
            {"lesson_revisions","skill_type='LISTENING'"},{"lesson_revisions","order_index=3"},
            {"lesson_revisions","audio_url='url'"},{"lesson_revisions","audio_public_id='public'"},
            {"lesson_revisions","is_placement_test=true"},{"lesson_revisions","xp_reward=22"},
            {"lesson_revisions","content=content||'tail'"},
            {"question_revisions","explanation='Explanation'"},{"question_revisions","knowledge_point='existing'"},
            {"question_revisions","difficulty='hard'"},{"question_revisions","xp_reward=30"},
            {"question_revisions","type='SHORT_ANSWER'"},{"question_revisions","order_index=order_index+2"},
            {"question_revisions","correct_answer='Answer'"},{"question_revisions","question_text=question_text||'tail'"},
            {"question_option_revisions","is_correct=true"},{"question_option_revisions","order_index=7"},
            {"question_option_revisions","option_text='Edited'"}
        };
        for (var mutation : mutations) {
            var before=capture(100);
            jdbc.update("update "+mutation[0]+" set "+mutation[1]);
            assertThat(capture(100).globalFingerprint()).as(mutation[0]+" "+mutation[1]).isNotEqualTo(before.globalFingerprint());
        }
        var before=capture(100);
        long first=before.manifestEntries().getFirst().questionRevisionId();
        jdbc.update("update question_revisions set explanation='own' where id=?",first);
        var after=capture(100);
        assertThat(after.manifestEntries().getFirst().perQuestionInputFingerprint()).isNotEqualTo(before.manifestEntries().getFirst().perQuestionInputFingerprint());
        assertThat(after.manifestEntries().get(1)).isEqualTo(before.manifestEntries().get(1));
        before=after;
        jdbc.update("insert into topic_revisions(revision_id,name) values (?,'Empty')",revision);
        assertThat(capture(100).globalFingerprint()).isNotEqualTo(before.globalFingerprint());
        before=capture(100);
        jdbc.update("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type) values (?,?,'Empty','READING')",topic,teacher);
        assertThat(capture(100).globalFingerprint()).isNotEqualTo(before.globalFingerprint());
        before=capture(100);
        jdbc.update("update topic_revisions set description='Edited empty' where name='Empty'");
        assertThat(capture(100).globalFingerprint()).isNotEqualTo(before.globalFingerprint());
        before=capture(100);
        jdbc.update("update lesson_revisions set content='Edited empty' where title='Empty'");
        assertThat(capture(100).globalFingerprint()).isNotEqualTo(before.globalFingerprint());
    }

    @Test void sourceIdsParentLinksAndContextDigestsAreCovered() {
        fixture(2);
        long liveTopic=jdbc.queryForObject("insert into topics(course_id,name) values (?,'Live') returning id",Long.class,course);
        long liveLesson=jdbc.queryForObject("insert into lessons(topic_id,teacher_id,title,skill_type,status) values (?,?,'Live','READING','DRAFT') returning id",Long.class,liveTopic,teacher);
        long liveQuestion=jdbc.queryForObject("insert into questions(lesson_id,teacher_id,type,question_text,status) values (?,?,'MULTIPLE_CHOICE','Live','DRAFT') returning id",Long.class,liveLesson,teacher);
        long liveOption=jdbc.queryForObject("insert into question_options(question_id,option_text) values (?,'Live') returning id",Long.class,liveQuestion);
        String[] tables={"topic_revisions","lesson_revisions","question_revisions","question_option_revisions"};
        String[] columns={"source_topic_id","source_lesson_id","source_question_id","source_option_id"};
        long[] sources={liveTopic,liveLesson,liveQuestion,liveOption};
        for(int i=0;i<4;i++) {
            var before=capture(100);
            jdbc.update("update "+tables[i]+" set "+columns[i]+"=?",sources[i]);
            var after=capture(100);
            assertThat(after.globalFingerprint()).isNotEqualTo(before.globalFingerprint());
            assertThat(after.manifestEntries().getFirst().perQuestionInputFingerprint()).isNotEqualTo(before.manifestEntries().getFirst().perQuestionInputFingerprint());
        }
        var before=capture(100);
        jdbc.update("update lesson_revisions set content=content||'parent tail'");
        assertThat(capture(100).manifestEntries().getFirst().perQuestionInputFingerprint()).isNotEqualTo(before.manifestEntries().getFirst().perQuestionInputFingerprint());
        long otherTopic=jdbc.queryForObject("insert into topic_revisions(revision_id,name) values (?,'Other') returning id",Long.class,revision);
        before=capture(100);
        jdbc.update("update lesson_revisions set topic_revision_id=?",otherTopic);
        assertThat(capture(100).globalFingerprint()).isNotEqualTo(before.globalFingerprint());
        long otherLesson=jdbc.queryForObject("insert into lesson_revisions(topic_revision_id,teacher_id,title,skill_type) values (?,?,'Other','READING') returning id",Long.class,otherTopic,teacher);
        before=capture(100);
        jdbc.update("update question_revisions set lesson_revision_id=?",otherLesson);
        assertThat(capture(100).globalFingerprint()).isNotEqualTo(before.globalFingerprint());
        var current=capture(100);
        long q1=current.manifestEntries().getFirst().questionRevisionId(),q2=current.manifestEntries().get(1).questionRevisionId();
        jdbc.update("update question_option_revisions set question_revision_id=? where question_revision_id=?",q2,q1);
        assertThat(capture(100).globalFingerprint()).isNotEqualTo(current.globalFingerprint());
    }

    @Test void singleCursorWithoutEntityLoadsOrDatabaseMutation() {
        fixture(250);
        var statistics=entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true); statistics.clear();
        var observed=org.mockito.Mockito.spy(new org.springframework.jdbc.core.JdbcTemplate(dataSource));
        var projection=new SubmissionProjectionReader(observed);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.setReadOnly(true); tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var before=jdbc.queryForList("select * from question_revisions order by id");
        tx.execute(status -> projection.capture(course,EnrichmentContract.V1,100));
        long queries=org.mockito.Mockito.mockingDetails(observed).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("query") && i.getMethod().getParameterCount()==3
                         && (i.getMethod().getParameterTypes()[1]==RowMapper.class
                         || i.getMethod().getParameterTypes()[2]==RowCallbackHandler.class)).count();
        assertThat(queries).isEqualTo(2); // one scalar header + one incremental event cursor
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(jdbc.queryForList("select * from question_revisions order by id")).isEqualTo(before);
        statistics.setStatisticsEnabled(false);
        assertThatThrownBy(() -> reader.batchContext(revision,java.util.List.of(-1L),EnrichmentContract.V1)).isInstanceOf(IllegalArgumentException.class);
    }
}
