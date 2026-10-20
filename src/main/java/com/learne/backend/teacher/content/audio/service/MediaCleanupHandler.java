package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.teacher.content.audio.dto.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Explicit registration only after the bounded delete adapter has passed its transport tests. */
public final class MediaCleanupHandler implements JobHandler {
    private final JobStore store;
    private final LessonAudioCleanupDecisionService decision;
    private final MediaDeletePort storage;
    private final ObjectMapper mapper;
    public MediaCleanupHandler(JobStore store,LessonAudioCleanupDecisionService decision,MediaDeletePort storage,ObjectMapper mapper) {
        this.store=store; this.decision=decision; this.storage=storage; this.mapper=mapper;
    }
    public JobType type() { return JobType.MEDIA_CLEANUP; }
    public int payloadVersion() { return 1; }
    public void execute(JobLease lease) {
        requireNoTransaction();
        if(!store.isLeaseCurrent(lease)) return;
        long course; LessonAudioState state;
        try {
            var node=mapper.reader().with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(lease.payloadJson());
            if(lease.type()!=type() || lease.payloadVersion()!=1 || !node.isObject() || node.size()!=4
                    || !node.path("courseId").isIntegralNumber() || !node.path("courseId").canConvertToLong()
                    || node.path("courseId").asLong()<=0 || !node.path("publicId").isString()
                    || !(node.path("url").isString() || node.path("url").isNull())
                    || !"VIDEO".equals(node.path("resourceType").asString())) throw new IllegalArgumentException();
            course=node.path("courseId").asLong();
            state=new LessonAudioState(node.path("url").isNull()?null:node.path("url").asString(),node.path("publicId").asString());
        } catch(Exception invalid) { throw new PermanentJobException("INVALID_INPUT"); }
        CleanupDecision outcome;
        try { outcome=decision.evaluate(course,state); }
        catch(RuntimeException databaseFailure) { throw new RetryableJobException("BUSY",null); }
        switch(outcome.kind()) {
            case RETAIN -> store.complete(lease,JobState.SKIPPED,outcome.reason());
            case RETRY -> throw new RetryableJobException("BUSY",null);
            case DELETE -> {
                requireNoTransaction();
                if(!store.isLeaseCurrent(lease)) return;
                storage.delete(state.publicId());
                store.complete(lease,JobState.SUCCEEDED,null);
            }
        }
    }
    public void failPermanently(JobLease lease,String code) { requireNoTransaction(); store.complete(lease,JobState.DEAD,code); }
    private static void requireNoTransaction() {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Media worker requires no ambient transaction");
    }
}
