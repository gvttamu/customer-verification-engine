package dev.guestbook.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.guestbook.verification.LaravelClient.Address;
import dev.guestbook.verification.LaravelClient.CustomerDetails;
import dev.guestbook.verification.LaravelClient.VerificationReport;
import java.util.List;
import java.util.Map;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.job.api.Job;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Runs the real BPMN process with the Laravel API mocked. The async executor is off,
 * so each test executes the queued jobs itself.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:verification-test;DB_CLOSE_DELAY=-1",
        "flowable.async-executor-activate=false",
})
class CustomerVerificationProcessTest {

    private static final String SANCTIONED_PHONE = "+1 555 010 0666";
    private static final String CLEAN_PHONE = "+44 20 7946 0000";

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private TaskService taskService;

    @MockitoBean
    private LaravelClient laravel;

    @Test
    void verifiesACustomerWithCleanDetails() {
        String processInstanceId = start(customer(CLEAN_PHONE, "12 St James Square", "SW1Y 4JH", "United Kingdom"));

        assertThat(reports()).singleElement().satisfies(report -> {
            assertThat(report.status()).isEqualTo("verified");
            assertThat(report.failedStep()).isNull();
            assertThat(report.matches()).isEmpty();
        });
        assertThat(taskService.createTaskQuery().processInstanceId(processInstanceId).count()).isZero();
        assertEnded(processInstanceId);
    }

    @Test
    void aSanctionedPhoneNumberWaitsForAContactReviewBeforeTheAddressIsChecked() {
        String processInstanceId = start(customer(SANCTIONED_PHONE, "1 Main Street", "12345", "United States"));

        VerificationReport report = reports().get(0);
        assertThat(report.status()).isEqualTo("flagged");
        assertThat(report.failedStep()).isEqualTo("contact");
        assertThat(report.matches()).containsExactly("Phone number +1 555 010 0666 is on the Demo sanctions list");
        assertThat(report.processInstanceId()).isEqualTo(processInstanceId);
        assertThat(report.runId()).isEqualTo(41L);

        Task review = openReview(processInstanceId);
        assertThat(review.getTaskDefinitionKey()).isEqualTo("reviewContact");
        assertThat(review.getName()).isEqualTo("Review contact match");
        assertThat(ran(processInstanceId, "screenAddress")).isFalse();
    }

    @Test
    void anApprovedContactMatchGoesOnToCheckTheAddressWhichNeedsItsOwnReview() {
        String processInstanceId = start(customer(SANCTIONED_PHONE, "13 Blackwater Road", "EC1A 1BB", "United Kingdom"));

        decide(processInstanceId, "approved");

        Task addressReview = openReview(processInstanceId);
        assertThat(addressReview.getTaskDefinitionKey()).isEqualTo("reviewAddress");
        assertThat(addressReview.getName()).isEqualTo("Review address match");
        assertThat(reports()).extracting(VerificationReport::status, VerificationReport::failedStep)
                .containsExactly(tuple("flagged", "contact"), tuple("flagged", "address"));
        assertThat(lastReport().matches())
                .containsExactly("Address 13 Blackwater Road, EC1A 1BB, United Kingdom is on the Demo sanctions list");

        decide(processInstanceId, "approved");

        assertThat(lastReport().status()).isEqualTo("approved");
        assertEnded(processInstanceId);
    }

    @Test
    void anApprovedContactMatchWithACleanAddressEndsApproved() {
        String processInstanceId = start(customer(SANCTIONED_PHONE, "1 Main Street", "12345", "United States"));

        decide(processInstanceId, "approved");

        assertThat(reports()).extracting(VerificationReport::status).containsExactly("flagged", "approved");
        assertThat(ran(processInstanceId, "screenAddress")).isTrue();
        assertEnded(processInstanceId);
    }

    @Test
    void aRejectedContactMatchEndsTheCheckWithoutCheckingTheAddress() {
        String processInstanceId = start(customer(SANCTIONED_PHONE, "13 Blackwater Road", "EC1A 1BB", "United Kingdom"));

        decide(processInstanceId, "rejected");

        assertThat(reports()).extracting(VerificationReport::status).containsExactly("flagged", "rejected");
        assertThat(ran(processInstanceId, "screenAddress")).isFalse();
        assertEnded(processInstanceId);
    }

    @Test
    void aSanctionedAddressAfterClearContactDetailsIsReviewedAndCanBeRejected() {
        String processInstanceId = start(customer(CLEAN_PHONE, "13 Blackwater Road", "EC1A 1BB", "United Kingdom"));

        assertThat(openReview(processInstanceId).getTaskDefinitionKey()).isEqualTo("reviewAddress");

        decide(processInstanceId, "rejected");

        assertThat(reports()).extracting(VerificationReport::status, VerificationReport::failedStep)
                .containsExactly(tuple("flagged", "address"), tuple("rejected", null));
        assertEnded(processInstanceId);
    }

    @Test
    void retriesAFailedCallInsteadOfFailingTheProcess() {
        String processInstanceId = runtimeService.startProcessInstanceByKey("customerVerification", "7", Map.of("customerId", 7L, "runId", 41L)).getId();
        when(laravel.fetchCustomer(anyLong())).thenThrow(new IllegalStateException("Laravel is down"));

        Job job = managementService.createJobQuery().processInstanceId(processInstanceId).singleResult();
        assertThatThrownBy(() -> managementService.executeJob(job.getId()))
                .isInstanceOf(FlowableException.class)
                .hasRootCauseMessage("Laravel is down");

        Job retried = managementService.createTimerJobQuery().processInstanceId(processInstanceId).singleResult();
        assertThat(retried.getRetries()).isEqualTo(2);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count()).isOne();
    }

    @Test
    void aProcessStartedWithoutARunIdReportsNone() {
        runtimeService.startProcessInstanceByKey("customerVerification", "7", Map.of("customerId", 7L));
        when(laravel.fetchCustomer(7)).thenReturn(customer(CLEAN_PHONE, "12 St James Square", "SW1Y 4JH", "United Kingdom"));

        runJobs();

        assertThat(lastReport().runId()).isNull();
    }

    private String start(CustomerDetails customer) {
        when(laravel.fetchCustomer(7)).thenReturn(customer);
        String processInstanceId = runtimeService.startProcessInstanceByKey("customerVerification", "7", Map.of("customerId", 7L, "runId", 41L)).getId();
        runJobs();

        return processInstanceId;
    }

    /**
     * Completes the open review the way the Laravel app does, then runs the steps that follow.
     */
    private void decide(String processInstanceId, String decision) {
        taskService.complete(openReview(processInstanceId).getId(), Map.of("reviewDecision", decision, "reviewNotes", "Checked"));
        runJobs();
    }

    private Task openReview(String processInstanceId) {
        return taskService.createTaskQuery().processInstanceId(processInstanceId).taskCandidateGroup("compliance").singleResult();
    }

    private boolean ran(String processInstanceId, String activityId) {
        return historyService.createHistoricActivityInstanceQuery().processInstanceId(processInstanceId).activityId(activityId).count() > 0;
    }

    private void assertEnded(String processInstanceId) {
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count()).isZero();
    }

    private void runJobs() {
        List<Job> jobs;
        while (!(jobs = managementService.createJobQuery().list()).isEmpty()) {
            jobs.forEach(job -> managementService.executeJob(job.getId()));
        }
    }

    /**
     * Every report sent to the Laravel app so far, oldest first.
     */
    private List<VerificationReport> reports() {
        ArgumentCaptor<VerificationReport> report = ArgumentCaptor.forClass(VerificationReport.class);
        verify(laravel, atLeastOnce()).reportVerification(eq(7L), report.capture());

        return report.getAllValues();
    }

    private VerificationReport lastReport() {
        List<VerificationReport> reports = reports();

        return reports.get(reports.size() - 1);
    }

    private static CustomerDetails customer(String phone, String line1, String postalCode, String country) {
        return new CustomerDetails(7, phone, "ada@example.com", new Address(line1, null, "Somewhere", postalCode, country), null, null);
    }
}
