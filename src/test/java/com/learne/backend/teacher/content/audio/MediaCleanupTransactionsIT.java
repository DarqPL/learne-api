package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.teacher.content.audio.dto.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.teacher.content.service.CourseWriteOutcome;
import com.learne.backend.domain.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import java.util.*;

class MediaCleanupTransactionsIT extends MediaCleanupIT {
    @Autowired LessonAudioPersistenceService persistence;
    @Autowired UserRepository users;
    @Autowired MediaCompensationService compensation;
    @MockitoSpyBean MediaCleanupProducer intents;

    @Test void removalCommitsIntentWithoutOldCallbackAndReplayDoesNotEnqueue() {
        var ids=fixture();
        long teacher=jdbc.queryForObject("select teacher_id from courses where id=?",Long.class,ids[0]);
        var user=users.findById(teacher).orElseThrow();
        persistence.remove(user,AudioTargetKind.REVISION,ids[1]);
        assertThat(jdbc.queryForObject("select audio_public_id from lesson_revisions where id=?",String.class,ids[1])).isNull();
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isOne();
        persistence.remove(user,AudioTargetKind.REVISION,ids[1]);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isOne();
        var destroyed=new ArrayList<String>();
        new MediaCleanupHandler(store,decisions,destroyed::add,mapper).execute(claim());
        assertThat(destroyed).containsExactly(LessonAudioCleanupServiceTests.state("a").publicId());
    }

    @Test void enqueueFailureRollsBackDetach() {
        var ids=fixture();
        long teacher=jdbc.queryForObject("select teacher_id from courses where id=?",Long.class,ids[0]);
        var target=org.springframework.test.util.AopTestUtils.<MediaCleanupProducer>getUltimateTargetObject(intents);
        doThrow(new IllegalStateException("queue unavailable")).when(target).enqueue(any(),eq(ids[0]),anyList());
        try {
            assertThatThrownBy(()->persistence.remove(users.findById(teacher).orElseThrow(),AudioTargetKind.REVISION,ids[1]))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("select audio_public_id from lesson_revisions where id=?",String.class,ids[1]))
                    .isEqualTo(LessonAudioCleanupServiceTests.state("a").publicId());
            assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isZero();
        } finally { reset(target); }
    }

    @Test void compensationRequiresRollbackEvidenceAndReusesOperationIdentity() {
        var ids=fixture(); var event=UUID.randomUUID(); var outcome=new CourseWriteOutcome();
        var assets=List.of(LessonAudioCleanupServiceTests.state("orphan"));
        compensation.enqueueConfirmedRollback(event,ids[0],assets,true,outcome);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isZero();
        var original=new IllegalStateException("original failure");
        assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(s->{
            outcome.observe(); throw original;
        })).isSameAs(original);
        compensation.enqueueConfirmedRollback(event,ids[0],assets,true,outcome);
        compensation.enqueueConfirmedRollback(event,ids[0],assets,true,outcome);
        assertThat(jdbc.queryForObject("select count(*) from background_jobs",Integer.class)).isOne();
        var destroyed=new ArrayList<String>();
        new MediaCleanupHandler(store,decisions,destroyed::add,mapper).execute(claim());
        assertThat(destroyed).containsExactly(assets.getFirst().publicId());
    }
}
