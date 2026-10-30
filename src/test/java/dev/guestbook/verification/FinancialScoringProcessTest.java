package dev.guestbook.verification;

import static dev.guestbook.verification.FinancialScorecardTest.financials;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.guestbook.verification.LaravelClient.Address;
import dev.guestbook.verification.LaravelClient.CustomerDetails;
import dev.guestbook.verification.LaravelClient.Financials;
import dev.guestbook.verification.LaravelClient.Loan;
import dev.guestbook.verification.LaravelClient.ScoreReport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RuntimeService;
import org.flowable.job.api.Job;
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
        "spring.datasource.url=jdbc:h2:mem:scoring-test;DB_CLOSE_DELAY=-1",
        "flowable.async-executor-activate=false",
})
class FinancialScoringProcessTest {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @MockitoBean
    private LaravelClient laravel;

    @Test
    void scoresTheCustomersFinancialsWithTheLoanRepaymentAndReportsTheScore() {
        String processInstanceId = start(financials("4200", null, "1350.50", "300", null, "900"), new Loan(new BigDecimal("15000"), 48, "home_improvement"));

        ScoreReport report = report();
        assertThat(report.score()).isEqualTo(55);
        assertThat(report.riskBand()).isEqualTo("medium");
        assertThat(report.debtToIncome()).isEqualTo(0.49);
        assertThat(report.disposableIncome()).isEqualTo(1254.49);
        assertThat(report.loanRepayment()).isEqualTo(395.01);
        assertThat(report.reasons()).first().asString().contains("adds about 395.01 a month at 12% a year");
        assertThat(report.processInstanceId()).isEqualTo(processInstanceId);
        assertThat(report.runId()).isEqualTo(42L);
        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).count()).isZero();
    }

    @Test
    void aCustomerWithoutFinancialsOrALoanIsScoredAsHavingNoIncome() {
        start(null, null);

        assertThat(report().score()).isZero();
        assertThat(report().debtToIncome()).isNull();
        assertThat(report().loanRepayment()).isNull();
        assertThat(report().reasons()).containsExactly("No income recorded");
    }

    private String start(Financials financials, Loan loan) {
        when(laravel.fetchCustomer(7)).thenReturn(new CustomerDetails(7, "+44 20 7946 0000", "ada@example.com",
                new Address("12 St James Square", null, "London", "SW1Y 4JH", "United Kingdom"), financials, loan));
        String processInstanceId = runtimeService.startProcessInstanceByKey("financialScoring", "7", Map.of("customerId", 7L, "runId", 42L)).getId();

        List<Job> jobs;
        while (!(jobs = managementService.createJobQuery().list()).isEmpty()) {
            jobs.forEach(job -> managementService.executeJob(job.getId()));
        }

        return processInstanceId;
    }

    private ScoreReport report() {
        ArgumentCaptor<ScoreReport> report = ArgumentCaptor.forClass(ScoreReport.class);
        verify(laravel).reportScore(eq(7L), report.capture());

        return report.getValue();
    }
}
