package com.learne.backend.teacher.content.audio;

@org.springframework.context.annotation.Import(com.learne.backend.testsupport.MediaTestDriver.class)
abstract class MediaPostgresTestSupport extends com.learne.backend.jobs.JobsPostgresTestSupport {
    @org.springframework.beans.factory.annotation.Autowired protected com.learne.backend.testsupport.MediaTestDriver media;
}
