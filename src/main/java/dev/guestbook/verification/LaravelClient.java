package dev.guestbook.verification;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls the Laravel app's customer API. Any error response throws, which fails the
 * current async job so Flowable retries it.
 */
@Component
public class LaravelClient {

    private final RestClient restClient;

    public LaravelClient(RestClient.Builder builder, LaravelProperties properties) {
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public CustomerDetails fetchCustomer(long customerId) {
        return restClient.get()
                .uri("/api/v1/customers/{id}", customerId)
                .retrieve()
                .body(CustomerDetails.class);
    }

    public void reportVerification(long customerId, VerificationReport report) {
        restClient.put()
                .uri("/api/v1/customers/{id}/verification", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(report)
                .retrieve()
                .toBodilessEntity();
    }

    public void reportScore(long customerId, ScoreReport report) {
        restClient.put()
                .uri("/api/v1/customers/{id}/scoring", customerId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(report)
                .retrieve()
                .toBodilessEntity();
    }

    public record CustomerDetails(long id, String phone, String email, Address address, Financials financials, Loan loan) {
    }

    /**
     * The loan the customer is applying for. Every part is null for a customer added
     * before loan details were captured.
     */
    public record Loan(
            BigDecimal amount,
            @JsonProperty("term_months") Integer termMonths,
            String purpose) {
    }

    /**
     * Monthly amounts. Any of them may be null: the customer left it blank, or was
     * added before financials were captured.
     */
    public record Financials(
            @JsonProperty("monthly_income") BigDecimal monthlyIncome,
            @JsonProperty("other_income") BigDecimal otherIncome,
            @JsonProperty("rent_or_mortgage") BigDecimal rentOrMortgage,
            @JsonProperty("car_payments") BigDecimal carPayments,
            @JsonProperty("other_debt_payments") BigDecimal otherDebtPayments,
            @JsonProperty("living_expenses") BigDecimal livingExpenses) {
    }

    /**
     * The result of one financial scoring run. {@code runId} plays the same part as in
     * {@link VerificationReport}.
     */
    public record ScoreReport(
            int score,
            @JsonProperty("risk_band") String riskBand,
            @JsonProperty("debt_to_income") Double debtToIncome,
            @JsonProperty("disposable_income") double disposableIncome,
            @JsonProperty("loan_repayment") Double loanRepayment,
            List<String> reasons,
            @JsonProperty("process_instance_id") String processInstanceId,
            @JsonProperty("run_id") Long runId) {
    }

    public record Address(
            String line1,
            String line2,
            String city,
            @JsonProperty("postal_code") String postalCode,
            String country) {
    }

    /**
     * A progress report from one verification run: "flagged" with the step and its
     * matches when a step finds a sanctions match, then the outcome ("verified",
     * "approved" or "rejected") when the run ends. {@code runId} is Laravel's id for the
     * run that started this process, so Laravel can match the report even before it has
     * stored the process instance id. Processes started before runs had ids report null.
     */
    public record VerificationReport(
            String status,
            @JsonProperty("failed_step") String failedStep,
            List<String> matches,
            @JsonProperty("process_instance_id") String processInstanceId,
            @JsonProperty("run_id") Long runId) {

        static VerificationReport outcome(String outcome, String processInstanceId, Long runId) {
            return new VerificationReport(outcome, null, List.of(), processInstanceId, runId);
        }

        static VerificationReport flagged(String failedStep, List<String> matches, String processInstanceId, Long runId) {
            return new VerificationReport("flagged", failedStep, matches, processInstanceId, runId);
        }
    }
}
