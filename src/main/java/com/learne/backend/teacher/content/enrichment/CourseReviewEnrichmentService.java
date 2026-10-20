package com.learne.backend.teacher.content.enrichment;

import com.learne.backend.jobs.service.PermanentJobException;
import com.learne.backend.shared.api.ApiException;
import com.learne.backend.teacher.content.enums.SkillType;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Bounded, pinned provider operation. Never traverses or mutates a revision graph. */
@Service
public class CourseReviewEnrichmentService {
    private final CourseKnowledgePointClient knowledgePointClient;
    private final QuestionDifficultyCalculator difficultyCalculator;
    private final QuestionXpPolicy xpPolicy;
    public CourseReviewEnrichmentService(CourseKnowledgePointClient client,QuestionDifficultyCalculator calculator,QuestionXpPolicy policy) {
        knowledgePointClient=client; difficultyCalculator=calculator; xpPolicy=policy;
    }
    public static String normalizeKnowledgePoint(String value) {
        if(value==null) throw invalidPoint();
        String normalized=value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+","_")
                .replaceAll("[^a-z0-9_]","").replaceAll("_+","_").replaceAll("^_+|_+$","");
        if(normalized.isBlank() || normalized.length()>64) throw invalidPoint();
        return normalized;
    }
    private static ApiException invalidPoint() { return new ApiException(HttpStatus.BAD_GATEWAY,"Invalid knowledge point."); }
    public Map<Long,String> enrichBatch(CourseKnowledgePointRequest request,EnrichmentContract contract) {
        if(!EnrichmentContract.V1.equals(contract)) throw new PermanentJobException("ENRICHMENT_CONTRACT_UNAVAILABLE");
        if(request==null || request.questions()==null || request.questions().isEmpty() || request.questions().size()>25)
            throw new PermanentJobException("HANDLER_ERROR");
        var expected=request.questions().stream().map(CourseKnowledgePointRequest.QuestionPayload::questionId).collect(Collectors.toSet());
        if(expected.contains(null) || expected.size()!=request.questions().size()) throw new PermanentJobException("HANDLER_ERROR");
        var response=knowledgePointClient.enrichKnowledgePoints(request,contract);
        if(response==null || response.items()==null) throw new PermanentJobException("HANDLER_ERROR");
        Map<Long,String> points=new HashMap<>();
        for(var item:response.items()) {
            if(item==null || item.questionId()==null || !expected.contains(item.questionId()) || points.containsKey(item.questionId()))
                throw new PermanentJobException("HANDLER_ERROR");
            try { points.put(item.questionId(),normalizeKnowledgePoint(item.knowledgePoint())); }
            catch(ApiException invalid) { throw new PermanentJobException("HANDLER_ERROR"); }
        }
        if(!points.keySet().equals(expected)) throw new PermanentJobException("HANDLER_ERROR");
        return Map.copyOf(points);
    }
    public record BatchResult(long questionId,String knowledgePoint,String difficulty,int xpReward) {}
    public List<BatchResult> enrichBatch(com.learne.backend.teacher.content.submission.SubmissionProjectionReader.BatchContext batch,EnrichmentContract contract) {
        var points=enrichBatch(batch.request(),contract);
        return batch.questions().stream().map(q -> {
            String difficulty=difficultyCalculator.calculate(q.courseLevel(),SkillType.valueOf(q.skillType()),q.lessonContent(),
                com.learne.backend.teacher.content.enums.QuestionType.valueOf(q.questionType()),q.questionText(),q.correctAnswer(),q.optionTexts());
            return new BatchResult(q.questionRevisionId(),points.get(q.questionRevisionId()),difficulty,xpPolicy.xpFor(difficulty));
        }).toList();
    }
}
