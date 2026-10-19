package com.learne.backend.testsupport;
import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobStore;
import com.learne.backend.teacher.content.submission.*;
import java.time.Duration;
import java.util.*;

/** Explicit real worker turns with a fake provider transport; no mock domain success. */
public class SubmissionTestDriver {
    private final SubmissionAcceptanceService acceptance;
    private final SubmissionRepository repository;
    private final CourseSubmissionHandler handler;
    private final JobStore store;
    public SubmissionTestDriver(SubmissionAcceptanceService acceptance,SubmissionRepository repository,CourseSubmissionHandler handler,JobStore store) {
        this.acceptance=acceptance; this.repository=repository; this.handler=handler; this.store=store;
    }
    public SubmissionResponse submit(long teacher,long course) { return finish(acceptance.accept(teacher,course).id()); }
    public SubmissionResponse finish(UUID id) {
        for(int turn=0;turn<100;turn++) {
            var summary=repository.findSummary(id).orElseThrow();
            if(!summary.editingLocked()) {
                if(!"SUCCEEDED".equals(summary.status())) throw new AssertionError("Submission failed: "+summary.failureCode());
                return summary;
            }
            var lease=store.claim(QueueName.AI,Map.of(JobType.COURSE_ENRICHMENT,Set.of(1)),Duration.ofSeconds(30)).orElseThrow();
            handler.execute(lease);
        }
        throw new AssertionError("Submission did not finish within 100 turns");
    }
}
