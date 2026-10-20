package com.learne.backend.teacher.content.audio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learne.backend.teacher.content.audio.service.*;
import com.learne.backend.jobs.service.*;
import com.learne.backend.storage.config.CloudinaryProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MediaCleanupConfigurationTests {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(MediaCleanupConfiguration.class)
                .withBean(JobStore.class,()->mock(JobStore.class))
                .withBean(JobMetrics.class,()->mock(JobMetrics.class))
                .withBean(LessonAudioCleanupDecisionService.class,()->mock(LessonAudioCleanupDecisionService.class))
                .withBean(ObjectMapper.class,ObjectMapper::new)
                .withBean(CloudinaryProperties.class,()->{
                    var properties=new CloudinaryProperties(); properties.setCloudName("test"); properties.setApiKey("synthetic"); properties.setApiSecret("synthetic"); return properties;
                });
    }
    @Test void explicitActivationRegistersRealBoundedAdapterAndVersionOneHandler() {
        runner().withPropertyValues("app.jobs.media-cleanup-enabled=true").run(context->{
            assertThat(context).hasSingleBean(CloudinaryMediaDeleteAdapter.class).hasSingleBean(MediaCleanupHandler.class);
            assertThat(context.getBean(MediaCleanupHandler.class).type()).isEqualTo(com.learne.backend.jobs.model.JobType.MEDIA_CLEANUP);
            assertThat(context.getBean(MediaCleanupHandler.class).payloadVersion()).isOne();
            var handler=context.getBean(MediaCleanupHandler.class);
            assertThat(com.learne.backend.jobs.maintenance.JobRetryCommand.SUPPORTED)
                    .containsEntry(handler.type(),handler.payloadVersion());
        });
    }
    @Test void disabledWorkersDoNotAdvertiseMediaSupport() {
        runner().run(context->assertThat(context).doesNotHaveBean(MediaCleanupHandler.class).doesNotHaveBean(MediaDeletePort.class));
    }
    @Test void enabledWithMissingCredentialsFailsStartup() {
        runner().withPropertyValues("app.jobs.media-cleanup-enabled=true")
                .withBean("invalidCloud",CloudinaryProperties.class,CloudinaryProperties::new,definition->definition.setPrimary(true))
                .run(context->{ assertThat(context).hasFailed(); assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class); });
    }
}
