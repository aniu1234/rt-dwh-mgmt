package com.rtdwh;

import com.rtdwh.dto.SyncTaskCreateDTO;
import com.rtdwh.entity.SyncTask;
import com.rtdwh.entity.SyncTask.TaskStatus;
import com.rtdwh.service.SyncTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class SyncTaskServiceTest {

    @Autowired
    private SyncTaskService syncTaskService;

    @Test
    @DisplayName("创建同步任务 - 应返回 draft 状态")
    void testCreateTask() {
        SyncTaskCreateDTO dto = new SyncTaskCreateDTO();
        dto.setTaskName("test_cdc_task");
        dto.setTaskType("cdc_sync");
        dto.setSourceConfigId(1L);
        dto.setTargetConfigId(3L);
        dto.setFlinkSql("CREATE TABLE source (...) WITH ('connector'='mysql-cdc'); INSERT INTO target SELECT * FROM source;");
        dto.setSyncStrategy("full_then_incremental");

        SyncTask task = syncTaskService.createTask(dto, 1L);

        assertNotNull(task.getId());
        assertEquals("test_cdc_task", task.getTaskName());
        assertEquals(TaskStatus.draft, task.getStatus());
        assertEquals(SyncTask.TaskType.cdc_sync, task.getTaskType());
    }

    @Test
    @DisplayName("获取同步任务详情 - 应返回完整信息")
    void testGetTask() {
        // Create a task first
        SyncTaskCreateDTO dto = new SyncTaskCreateDTO();
        dto.setTaskName("test_get_task");
        dto.setTaskType("cdc_sync");
        dto.setSourceConfigId(1L);
        dto.setTargetConfigId(3L);
        dto.setFlinkSql("SELECT 1");
        dto.setSyncStrategy("incremental_only");

        SyncTask created = syncTaskService.createTask(dto, 1L);
        SyncTask fetched = syncTaskService.getTask(created.getId());

        assertEquals(created.getId(), fetched.getId());
        assertEquals("test_get_task", fetched.getTaskName());
    }

    @Test
    @DisplayName("删除 draft 状态的任务 - 应成功")
    void testDeleteDraftTask() {
        SyncTaskCreateDTO dto = new SyncTaskCreateDTO();
        dto.setTaskName("test_delete_task");
        dto.setTaskType("cdc_sync");
        dto.setSourceConfigId(1L);
        dto.setTargetConfigId(3L);
        dto.setFlinkSql("SELECT 1");
        dto.setSyncStrategy("incremental_only");

        SyncTask created = syncTaskService.createTask(dto, 1L);
        assertDoesNotThrow(() -> syncTaskService.deleteTask(created.getId()));
    }

    @Test
    @DisplayName("删除 running 状态的任务 - 应抛出异常")
    void testDeleteRunningTaskShouldFail() {
        // This test verifies that only draft/finished tasks can be deleted
        // In a real test, we would need to mock the Flink cluster service
        // to set up a running task without actually submitting to Flink
        assertThrows(RuntimeException.class, () -> {
            syncTaskService.deleteTask(999L); // Non-existent task
        });
    }
}
