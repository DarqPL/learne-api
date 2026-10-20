package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.jobs.model.*;
import com.learne.backend.jobs.service.JobEnqueuer;
import com.learne.backend.teacher.content.audio.dto.LessonAudioState;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** Only new mutation events enter here. Historical events are never replayed after 30-day purge. */
@Service
public class MediaCleanupProducer {
    private final JobEnqueuer queue;
    private final ObjectMapper mapper;
    public MediaCleanupProducer(JobEnqueuer queue,ObjectMapper mapper) { this.queue=queue; this.mapper=mapper; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(UUID mutationId,long courseId,List<LessonAudioState> candidates) {
        Objects.requireNonNull(mutationId);
        if(courseId<=0) throw new IllegalArgumentException("Invalid cleanup course");
        if(candidates==null) return;
        var seen=new HashSet<String>();
        for(var state:candidates) {
            if(state==null || state.publicId()==null || !seen.add(state.publicId())) continue;
            var payload=mapper.createObjectNode().put("courseId",courseId).put("url",state.url())
                    .put("publicId",state.publicId()).put("resourceType","VIDEO");
            queue.enqueue(new JobSpec(UUID.randomUUID(),QueueName.MEDIA,JobType.MEDIA_CLEANUP,1,0,
                    "media-cleanup:"+mutationId+":"+digest(state.publicId()),mapper.writeValueAsString(payload),null,null));
        }
    }
    static String digest(String publicId) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:List.of("VIDEO",publicId)) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
