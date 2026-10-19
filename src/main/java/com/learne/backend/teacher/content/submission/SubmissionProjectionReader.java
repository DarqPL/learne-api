package com.learne.backend.teacher.content.submission;

import com.learne.backend.teacher.content.enrichment.CourseKnowledgePointRequest;
import com.learne.backend.teacher.content.enrichment.EnrichmentContract;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** SQL-only projection. Joins the caller's transaction; standalone reads use a consistent snapshot.
 * Acceptance callers must lock the course before reading. No locks or entity graphs are loaded here.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 90)
public class SubmissionProjectionReader {
    private final JdbcTemplate jdbc;
    public SubmissionProjectionReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Header(long courseId, long revisionId, int revisionNumber, long teacherId, String status,
            String name, String description, String type, String level, int orderIndex, String pricingType,
            Long proposedPrice, int questionCount) { }
    public record ManifestEntry(long questionRevisionId, int ordinal, String perQuestionInputFingerprint) { }
    public record Capture(Header header, String globalFingerprint, List<ManifestEntry> manifestEntries) {
        public Capture { manifestEntries = List.copyOf(manifestEntries); }
    }
    /** Full original text and sufficient original option inputs for the existing difficulty calculator.
     * At most eight nonblank options are retained: its only option predicate is count >= 4.
     */
    public record QuestionContext(long questionRevisionId, String inputFingerprint, String courseLevel,
            String skillType, String lessonContent, String questionType, String questionText,
            String correctAnswer, List<String> optionTexts) {
        public QuestionContext { optionTexts = List.copyOf(optionTexts); }
    }
    public record BatchContext(CourseKnowledgePointRequest request, List<QuestionContext> questions) {
        public BatchContext { questions = List.copyOf(questions); }
    }
    public Optional<Header> latestHeader(long courseId) {
        return jdbc.query(SubmissionProjectionSql.HEADER+" where r.course_id=? order by r.revision_number desc,r.id desc limit 1",
                SubmissionProjectionReader::header,courseId).stream().findFirst();
    }
    public Capture capture(long courseId, EnrichmentContract contract) { return capture(courseId,contract,100); }
    /** pageSize is the JDBC cursor fetch size (1..100); the ordered event query executes once. */
    public Capture capture(long courseId, EnrichmentContract contract, int pageSize) {
        var header=latestHeader(courseId).orElseThrow(() -> new IllegalArgumentException("Revision not found"));
        requireSize(pageSize);
        if (header.questionCount()>1000) throw new IllegalArgumentException("Revision exceeds question limit");
        return scan(header,contract,pageSize,Set.of()).capture();
    }
    /** IDs must be distinct and belong to exactly this revision. Results use canonical hierarchy order.
     * Streams only selected questions, distinct ancestors, and ALL options with fetch size 100.
     * Recomputes the same per-question fingerprints used by capture, without any metadata writes.
     */
    public BatchContext batchContext(long revisionId, List<Long> questionIds, EnrichmentContract contract) {
        if (questionIds == null || questionIds.isEmpty() || questionIds.size()>25 || questionIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(questionIds).size()!=questionIds.size()) throw new IllegalArgumentException("Invalid question batch");
        // This internal header has an unused count sentinel; public header/capture reads always count.
        var header=jdbc.query(SubmissionProjectionSql.BATCH_HEADER+" where r.id=?",SubmissionProjectionReader::header,revisionId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Revision not found"));
        var result=scan(header,contract,100,Set.copyOf(questionIds));
        if (result.contexts.size()!=questionIds.size()) throw new IllegalArgumentException("Question batch does not belong to revision");
        var course=new CourseKnowledgePointRequest.CoursePayload(header.courseId(),bounded(header.name(),255),
                bounded(header.description(),1000),header.type(),bounded(header.level(),32));
        return new BatchContext(new CourseKnowledgePointRequest(course,List.copyOf(result.payloads)),result.contexts);
    }
    private Scan scan(Header header, EnrichmentContract contract, int size, Set<Long> requested) {
        var scan=new Scan(header,contract,requested);
        var parameters=new ArrayList<Object>(26);
        parameters.add(header.revisionId());
        if(!requested.isEmpty()) {
            parameters.addAll(requested);
            while(parameters.size()<26) parameters.add(null);
        }
        String sql=requested.isEmpty()?SubmissionProjectionSql.EVENTS:SubmissionProjectionSql.BATCH_EVENTS;
        // JDBC's default prepared ResultSet is forward-only/read-only. A positive fetch size with
        // autocommit disabled activates PostgreSQL's server cursor rather than buffering all rows.
        jdbc.query(sql, statement -> {
            if(statement.getConnection().getAutoCommit())
                throw new IllegalStateException("Projection cursor requires an active transaction");
            statement.setFetchSize(size);
            for(int i=0;i<parameters.size();i++) statement.setObject(i+1,parameters.get(i));
        }, (RowCallbackHandler)rs -> scan.accept(SubmissionProjectionEvent.read(rs,0)));
        scan.finishQuestion();
        return scan;
    }
    private static void requireSize(int size) {
        if(size<1 || size>100) throw new IllegalArgumentException("Page size must be between 1 and 100");
    }
    private static Header header(ResultSet rs,int row) throws SQLException {
        return new Header(rs.getLong(1),rs.getLong(2),rs.getInt(3),rs.getLong(4),rs.getString(5),rs.getString(6),
                rs.getString(7),rs.getString(8),rs.getString(9),rs.getInt(10),rs.getString(11),rs.getObject(12,Long.class),rs.getInt(13));
    }
    private static void appendHeader(SubmissionFingerprint hash,Header h) {
        hash.add("revision"); hash.add(h.courseId()); hash.add(h.revisionId()); hash.add(h.revisionNumber());
        hash.add(h.name()); hash.add(h.description()); hash.add(h.type()); hash.add(h.level()); hash.add(h.orderIndex());
        hash.add(h.pricingType()); hash.add(h.proposedPrice());
    }
    static String bounded(String text,int limit) {
        if(text==null) return "";
        String trimmed=text.trim(); return trimmed.length()<=limit?trimmed:trimmed.substring(0,limit);
    }
    private static final class Scan {
        final Header header;
        final EnrichmentContract contract;
        final Set<Long> requested;
        final SubmissionFingerprint global;
        final String headerDigest;
        final List<ManifestEntry> manifest=new ArrayList<>();
        final List<QuestionContext> contexts=new ArrayList<>();
        final List<CourseKnowledgePointRequest.QuestionPayload> payloads=new ArrayList<>();
        SubmissionProjectionEvent topic,lesson,question;
        String topicDigest,lessonDigest;
        SubmissionFingerprint questionHash;
        final List<CourseKnowledgePointRequest.OptionPayload> options=new ArrayList<>();
        final List<String> originalOptions=new ArrayList<>();
        Scan(Header header,EnrichmentContract contract,Set<Long> requested) {
            this.header=header; this.contract=contract; this.requested=requested;
            global=new SubmissionFingerprint(contract,"revision"); appendHeader(global,header);
            var h=new SubmissionFingerprint(contract,"header"); appendHeader(h,header); headerDigest=h.finish();
        }
        void accept(SubmissionProjectionEvent event) {
            if(!event.kind().equals("option")) finishQuestion();
            event.append(global);
            switch(event.kind()) {
                case "topic" -> { topic=event; topicDigest=event.fingerprint(contract); }
                case "lesson" -> { lesson=event; lessonDigest=event.fingerprint(contract); }
                case "question" -> {
                    question=event; questionHash=new SubmissionFingerprint(contract,"question-input");
                    questionHash.add(headerDigest); questionHash.add(topicDigest); questionHash.add(lessonDigest);
                    event.append(questionHash);
                }
                case "option" -> {
                    event.append(questionHash);
                    if(requested.contains(question.id()) && event.a()!=null && !event.a().trim().isBlank() && options.size()<8) {
                        options.add(new CourseKnowledgePointRequest.OptionPayload(bounded(event.a(),250),Boolean.TRUE.equals(event.flag()),event.order()));
                        originalOptions.add(event.a());
                    }
                }
                default -> throw new IllegalStateException("Unknown projection event");
            }
        }
        void finishQuestion() {
            if(question==null) return;
            String fingerprint=questionHash.finish();
            if(manifest.size()>=1000) throw new IllegalArgumentException("Revision exceeds question limit");
            manifest.add(new ManifestEntry(question.id(),manifest.size(),fingerprint));
            if(requested.contains(question.id())) {
                contexts.add(new QuestionContext(question.id(),fingerprint,header.level(),lesson.d(),lesson.c(),question.a(),question.b(),question.d(),originalOptions));
                payloads.add(new CourseKnowledgePointRequest.QuestionPayload(question.id(),topic.sourceId()==null?topic.id():topic.sourceId(),topic.order(),
                        bounded(topic.a(),255),bounded(topic.b(),1000),lesson.sourceId()==null?lesson.id():lesson.sourceId(),lesson.order(),
                        bounded(lesson.a(),255),bounded(lesson.b(),1000),bounded(lesson.c(),2000),lesson.d(),question.order(),question.a(),
                        bounded(question.b(),1000),bounded(question.d(),500),List.copyOf(options)));
            }
            question=null; questionHash=null; options.clear(); originalOptions.clear();
        }
        Capture capture() { return new Capture(header,global.finish(),manifest); }
    }
}
