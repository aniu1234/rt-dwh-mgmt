package com.rtdwh.config;

import com.rtdwh.job.FlinkJobStatusMonitorJob;
import org.quartz.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuartzJobConfig {

    @Bean
    public JobDetail flinkStatusMonitorJobDetail() {
        return JobBuilder.newJob(FlinkJobStatusMonitorJob.class)
                .withIdentity("flinkStatusMonitorJob", "monitorGroup")
                .storeDurably()
                .requestRecovery()
                .withDescription("定时轮询 Flink 集群状态并同步任务状态到 DB")
                .build();
    }

    @Bean
    public Trigger flinkStatusMonitorTrigger() {
        // Every 30 seconds
        SimpleScheduleBuilder schedule = SimpleScheduleBuilder.simpleSchedule()
                .withIntervalInSeconds(30)
                .repeatForever()
                .withMisfireHandlingInstructionNowWithExistingCount();

        return TriggerBuilder.newTrigger()
                .forJob(flinkStatusMonitorJobDetail())
                .withIdentity("flinkStatusMonitorTrigger", "monitorGroup")
                .withSchedule(schedule)
                .withDescription("每30秒轮询 Flink 任务状态")
                .build();
    }
}
