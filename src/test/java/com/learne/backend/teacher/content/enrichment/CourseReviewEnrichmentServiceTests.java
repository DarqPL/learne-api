package com.learne.backend.teacher.content.enrichment;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.service.PermanentJobException;

class CourseReviewEnrichmentServiceTests {
    private CourseKnowledgePointRequest request() {
        return new CourseKnowledgePointRequest(null,List.of(new CourseKnowledgePointRequest.QuestionPayload(
            1L,1L,0,"Topic","",1L,0,"Lesson","","Text","READING",0,"SHORT_ANSWER","Question","Answer",List.of())));
    }
    private CourseReviewEnrichmentService service(CourseKnowledgePointClient client) {
        return new CourseReviewEnrichmentService(client,new QuestionDifficultyCalculator(),new QuestionXpPolicy());
    }
    @Test void normalizesKnowledgePoint() {
        assertThat(CourseReviewEnrichmentService.normalizeKnowledgePoint(" Listening Dates ")).isEqualTo("listening_dates");
        assertThat(CourseReviewEnrichmentService.normalizeKnowledgePoint("pronunciation-th sounds!")).isEqualTo("pronunciation_th_sounds");
        for(String invalid:List.of(" !!! ","x".repeat(65))) assertThatThrownBy(()->CourseReviewEnrichmentService.normalizeKnowledgePoint(invalid)).isInstanceOf(com.learne.backend.shared.api.ApiException.class);
    }
    @Test void pinsContractAndValidatesExactResponseSet() {
        var good=service((request,contract)-> { assertThat(contract).isEqualTo(EnrichmentContract.V1); return new CourseKnowledgePointResponse(List.of(new CourseKnowledgePointResponse.Item(1L," Test Point "))); });
        assertThat(good.enrichBatch(request(),EnrichmentContract.V1)).containsEntry(1L,"test_point");
        for(var items:List.of(List.<CourseKnowledgePointResponse.Item>of(),List.of(new CourseKnowledgePointResponse.Item(2L,"point")),List.of(new CourseKnowledgePointResponse.Item(1L,"point"),new CourseKnowledgePointResponse.Item(1L,"point")),List.of(new CourseKnowledgePointResponse.Item(1L,"!!!")))) {
            assertThatThrownBy(()->service((r,c)->new CourseKnowledgePointResponse(items)).enrichBatch(request(),EnrichmentContract.V1)).isInstanceOf(PermanentJobException.class);
        }
    }
    @Test void rejectsOversizedBatchBeforeProvider() {
        var request=request();
        assertThatThrownBy(()->service((r,c)-> { throw new AssertionError("Provider called"); }).enrichBatch(
            new CourseKnowledgePointRequest(null,Collections.nCopies(26,request.questions().getFirst())),EnrichmentContract.V1)).isInstanceOf(PermanentJobException.class);
    }
}
