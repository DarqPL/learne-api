package com.learne.backend.jobs.maintenance;

import com.learne.backend.jobs.model.JobType;
import com.learne.backend.mail.service.*;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import com.learne.backend.teacher.content.audio.service.LessonAudioCleanupDecisionService;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;

/** Only restores an eligible retained envelope. Never invokes handlers or provider clients. */
public final class JobRetryCommand implements ApplicationRunner, ExitCodeGenerator {
    public static final Map<JobType,Integer> SUPPORTED = Map.of(
            JobType.PAYMENT_CONFIRMATION_MAIL,1, JobType.COURSE_PENDING_REVIEW_MAIL,1,
            JobType.COURSE_REVIEW_RESULT_MAIL,1, JobType.MEDIA_CLEANUP,1);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MailPayloadCodec codec;
    private final MailEligibilityService eligibility;
    private final ObjectMapper mapper;
    private final String cloud;
    private int exit = 2;
    public JobRetryCommand(JdbcTemplate jdbc, PlatformTransactionManager tx, MailPayloadCodec codec,
            MailEligibilityService eligibility, ObjectMapper mapper, String cloud) {
        this.jdbc=jdbc; this.transaction=new TransactionTemplate(tx); transaction.setTimeout(5);
        this.codec=codec; this.eligibility=eligibility; this.mapper=mapper; this.cloud=cloud;
    }
    @Override public void run(ApplicationArguments args) {
        exit=2;
        try {
            if (!args.getNonOptionArgs().isEmpty()) throw new IllegalArgumentException();
            var allowed=Set.of("app.jobs.maintenance.retry-job","app.jobs.maintenance.reason","app.jobs.maintenance.operator");
            if(args.getOptionNames().stream().anyMatch(name -> name.startsWith("app.jobs.maintenance.") && !allowed.contains(name)))
                throw new IllegalArgumentException();
            String raw=option(args,"retry-job");
            UUID id=UUID.fromString(raw);
            if (!id.toString().equals(raw)) throw new IllegalArgumentException();
            retry(id,option(args,"reason"),option(args,"operator"));
            exit=0;
        } catch (RuntimeException rejected) { LoggerFactory.getLogger(getClass()).warn("JOB_RETRY_REJECTED"); }
    }
    private static String option(ApplicationArguments args,String name) {
        var values=args.getOptionValues("app.jobs.maintenance."+name);
        if(values==null || values.size()!=1) throw new IllegalArgumentException();
        return values.getFirst();
    }
    public void retry(UUID id,String reason,String operator) {
        // Audit fields are bounded incident/operator identifiers, never free-form payload or credentials.
        if(id==null || reason==null || !reason.matches("[A-Z][A-Z0-9_-]{2,63}")
                || operator==null || !operator.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException();
        String type=transaction.execute(status -> {
            var before=one(id,false);
            JobType kind=validate(before);
            if(kind==JobType.MEDIA_CLEANUP) media(before);
            else {
                var payload=codec.decode(kind,(String)before.get("payload"));
                // Readers join this transaction, with all domain locks acquired before the job lock.
                jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",payload.recipientUserId());
                if(payload.orderId()!=null) jdbc.queryForList("SELECT id FROM orders WHERE id=? FOR UPDATE",payload.orderId());
                if(payload.courseId()!=null && kind==JobType.COURSE_PENDING_REVIEW_MAIL) {
                    jdbc.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE",payload.courseId());
                }
                if(eligibility.checkNoticeInCurrentTransaction(payload)!=MailEligibilityService.Result.ALLOW)
                    throw new IllegalArgumentException();
            }
            var locked=one(id,true);
            validate(locked);
            if(!before.equals(locked)) throw new IllegalArgumentException();
            if(jdbc.update("""
                    UPDATE background_jobs SET state='READY',attempts=0,available_at=clock_timestamp(),
                        completed_at=NULL,lease_token=NULL,lease_until=NULL,last_error_code=NULL,updated_at=clock_timestamp()
                    WHERE id=? AND state IN ('SUCCEEDED','SKIPPED','DEAD')
                    """,id)!=1) throw new IllegalArgumentException();
            return kind.name();
        });
        LoggerFactory.getLogger(getClass()).info("JOB_RETRY job={} type={} operator={} reason={}",id,type,operator,reason);
    }
    private Map<String,Object> one(UUID id,boolean lock) {
        var rows=jdbc.queryForList("SELECT id,queue,type,payload_version,state,payload::text AS payload,secret_key_id,secret_nonce,secret_ciphertext,expires_at FROM background_jobs WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.size()!=1) throw new IllegalArgumentException();
        return rows.getFirst();
    }
    private JobType validate(Map<String,Object> row) {
        JobType type=JobType.valueOf((String)row.get("type"));
        if(!Objects.equals(SUPPORTED.get(type),row.get("payload_version"))
                || !Set.of("SUCCEEDED","SKIPPED","DEAD").contains(row.get("state"))
                || !Objects.equals(row.get("queue"),type==JobType.MEDIA_CLEANUP?"MEDIA":"MAIL_NOTICE")
                || row.get("secret_key_id")!=null || row.get("secret_nonce")!=null || row.get("secret_ciphertext")!=null
                || row.get("expires_at")!=null) throw new IllegalArgumentException();
        return type;
    }
    private void media(Map<String,Object> row) {
        var node=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree((String)row.get("payload"));
        if(node==null || !node.isObject() || node.size()!=4 || !node.path("courseId").isIntegralNumber()
                || !node.path("courseId").canConvertToLong() || node.path("courseId").asLong()<=0
                || !node.path("publicId").isString() || !node.path("url").isString()
                || !"VIDEO".equals(node.path("resourceType").asString())) throw new IllegalArgumentException();
        var state=new LessonAudioState(node.path("url").asString(),node.path("publicId").asString());
        if(!LessonAudioCleanupDecisionService.hasManagedProvenance(state,cloud)) throw new IllegalArgumentException();
        if(jdbc.queryForList("SELECT id FROM courses WHERE id=? FOR UPDATE",node.path("courseId").asLong()).isEmpty())
            throw new IllegalArgumentException();
        for(String table:List.of("lessons","lesson_revisions")) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+
                    " WHERE audio_public_id=? OR audio_url=?)",Boolean.class,state.publicId(),state.url())))
                throw new IllegalArgumentException();
            var urls=jdbc.queryForList("SELECT DISTINCT audio_url FROM "+table+" WHERE audio_url IS NOT NULL",String.class);
            if(LessonAudioCleanupDecisionService.hasUncertainReference(state.publicId(),urls)) throw new IllegalArgumentException();
        }
    }
    @Override public int getExitCode() { return exit; }
}
