package com.rtdwh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rtdwh.entity.TableMaintenanceLog;
import com.rtdwh.entity.TableMaintenanceLog.Status;
import com.rtdwh.repository.TableMaintenanceLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaimonMaintenanceServiceTest {
    private final TableMaintenanceLogRepository repository = mock(TableMaintenanceLogRepository.class);
    private final RestTemplate rest = mock(RestTemplate.class);
    private final PaimonMaintenanceService service = new PaimonMaintenanceService(repository, rest, new ObjectMapper());
    private final TableMaintenanceLog entry = TableMaintenanceLog.builder().id(1L).tableMetaId(2L)
            .sqlContent("CALL sys.compact('ods.orders')").startedAt(LocalDateTime.now()).build();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "gateway", "http://gateway");
        ReflectionTestUtils.setField(service, "flink", "http://flink");
        for (String field : List.of("jdbcUri", "jdbcUser", "jdbcPassword", "warehouse", "catalog"))
            ReflectionTestUtils.setField(service, field, "test");
        when(repository.findByStatus(any())).thenAnswer(call -> entry.getStatus() == call.getArgument(0) ? List.of(entry) : List.of());
        when(rest.postForObject(eq("http://gateway/v1/sessions"), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"sessionHandle\":\"session-1\"}");
        when(rest.postForObject(eq("http://gateway/v1/sessions/session-1/statements"), any(HttpEntity.class), eq(String.class)))
                .thenReturn("{\"operationHandle\":\"catalog-op\"}", "{\"operationHandle\":\"use-op\"}", "{\"operationHandle\":\"call-op\"}");
        when(rest.getForObject(contains("/status"), eq(String.class))).thenReturn("{\"status\":\"FINISHED\"}");
    }

    private void submitCall() {
        service.start(entry);
        service.reconcile();
        service.reconcile();
        assertEquals("CALL", entry.getExecutionPhase());
    }

    @Test void doesNotCloseSessionUntilCallActuallyFinishes() {
        submitCall();
        verify(rest, never()).delete(anyString());
        when(rest.getForObject(contains("/call-op/status"), eq(String.class))).thenReturn("{\"status\":\"RUNNING\"}");
        service.reconcile();
        assertEquals(Status.running, entry.getStatus());
        when(rest.getForObject(contains("/call-op/status"), eq(String.class))).thenReturn("{\"status\":\"FINISHED\"}");
        when(rest.getForObject(contains("/result/0"), eq(String.class)))
                .thenReturn("{\"resultType\":\"PAYLOAD\",\"results\":{\"data\":[{\"fields\":[\"Success\"]}]}}");
        service.reconcile();
        assertEquals(Status.success, entry.getStatus());
        assertNotNull(entry.getFinishedAt());
        verify(rest).delete("http://gateway/v1/sessions/session-1");
    }

    @Test void gatewayFailureIsTerminalFailure() {
        submitCall();
        when(rest.getForObject(contains("/call-op/status"), eq(String.class))).thenReturn("{\"status\":\"ERROR\"}");
        service.reconcile();
        assertEquals(Status.failed, entry.getStatus());
        assertNotNull(entry.getFinishedAt());
    }

    @Test void lostSubmitResponseIsNotRetriedOrCleanedUp() {
        when(rest.postForObject(contains("/statements"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("connection lost"));
        service.start(entry);
        service.reconcile();
        assertEquals(Status.unknown, entry.getStatus());
        assertNull(entry.getOperationId());
        verify(rest, times(1)).postForObject(contains("/statements"), any(HttpEntity.class), eq(String.class));
        verify(rest, never()).delete(anyString());
    }

    @Test void timeoutRemainsObservableAndDoesNotCancelJob() {
        submitCall();
        entry.setStartedAt(LocalDateTime.now().minusHours(1));
        when(rest.getForObject(contains("/call-op/status"), eq(String.class))).thenReturn("{\"status\":\"RUNNING\"}");
        service.reconcile();
        assertEquals(Status.timed_out, entry.getStatus());
        assertNull(entry.getFinishedAt());
        verify(rest, never()).delete(anyString());
    }

    @Test void jobReturnedByProcedureMustFinishBeforeSuccess() {
        submitCall();
        String jobId = "0123456789abcdef0123456789abcdef";
        when(rest.getForObject(contains("/result/0"), eq(String.class)))
                .thenReturn("{\"results\":{\"data\":[{\"fields\":[\"JobID=" + jobId + "\"]}]}}");
        service.reconcile();
        assertEquals(jobId, entry.getFlinkJobId());
        assertEquals(Status.running, entry.getStatus());
        when(rest.getForObject("http://flink/jobs/" + jobId, String.class)).thenReturn("{\"state\":\"FAILED\"}");
        service.reconcile();
        assertEquals(Status.failed, entry.getStatus());
    }

    @Test void interruptedObservationRetainsHandlesForRecovery() {
        submitCall();
        when(rest.getForObject(contains("/call-op/status"), eq(String.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("offline"));
        service.reconcile();
        assertEquals(Status.unknown, entry.getStatus());
        assertEquals("call-op", entry.getOperationId());
        verify(rest, never()).delete(anyString());
    }
}
