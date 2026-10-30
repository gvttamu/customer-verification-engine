package dev.guestbook.verification;

import dev.guestbook.verification.LaravelClient.CustomerDetails;
import dev.guestbook.verification.LaravelClient.VerificationReport;
import java.util.ArrayList;
import java.util.List;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service tasks of the customer verification process, referenced by bean name
 * from customer-verification.bpmn20.xml.
 */
@Configuration
public class VerificationTasks {

    @Bean
    JavaDelegate fetchCustomerDetails(LaravelClient laravel) {
        return execution -> {
            CustomerDetails customer = laravel.fetchCustomer(customerId(execution));

            execution.setVariable("phone", customer.phone());
            execution.setVariable("email", customer.email());
            execution.setVariable("addressLine1", customer.address().line1());
            execution.setVariable("city", customer.address().city());
            execution.setVariable("postalCode", customer.address().postalCode());
            execution.setVariable("country", customer.address().country());
        };
    }

    @Bean
    JavaDelegate screenContactDetails(SanctionsRegistry sanctions) {
        return execution -> execution.setVariable("contactMatches", new ArrayList<>(sanctions.screenContact(
                (String) execution.getVariable("phone"),
                (String) execution.getVariable("email"))));
    }

    @Bean
    JavaDelegate screenAddressDetails(SanctionsRegistry sanctions) {
        return execution -> execution.setVariable("addressMatches", new ArrayList<>(sanctions.screenAddress(
                (String) execution.getVariable("addressLine1"),
                (String) execution.getVariable("postalCode"),
                (String) execution.getVariable("country"))));
    }

    /**
     * Reports the contact step's matches, so the customer app can show them to the
     * compliance officer who reviews them.
     */
    @Bean
    JavaDelegate reportContactMatches(LaravelClient laravel) {
        return execution -> laravel.reportVerification(customerId(execution), VerificationReport.flagged(
                "contact", matches(execution, "contactMatches"), execution.getProcessInstanceId(), runId(execution)));
    }

    /**
     * Reports the address step's matches for review.
     */
    @Bean
    JavaDelegate reportAddressMatches(LaravelClient laravel) {
        return execution -> laravel.reportVerification(customerId(execution), VerificationReport.flagged(
                "address", matches(execution, "addressMatches"), execution.getProcessInstanceId(), runId(execution)));
    }

    /**
     * Reports how the check ended: "rejected" when a reviewer rejected a match,
     * "approved" when every match was reviewed and approved, otherwise "verified".
     */
    @Bean
    JavaDelegate reportVerificationOutcome(LaravelClient laravel) {
        return execution -> {
            String outcome = outcome(execution);

            laravel.reportVerification(customerId(execution),
                    VerificationReport.outcome(outcome, execution.getProcessInstanceId(), runId(execution)));
            execution.setVariable("verificationOutcome", outcome);
        };
    }

    static String outcome(DelegateExecution execution) {
        if ("rejected".equals(execution.getVariable("reviewDecision"))) {
            return "rejected";
        }

        boolean anyMatches = !matches(execution, "contactMatches").isEmpty() || !matches(execution, "addressMatches").isEmpty();

        return anyMatches ? "approved" : "verified";
    }

    static long customerId(DelegateExecution execution) {
        return ((Number) execution.getVariable("customerId")).longValue();
    }

    static Long runId(DelegateExecution execution) {
        return execution.getVariable("runId") instanceof Number runId ? runId.longValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> matches(DelegateExecution execution, String variable) {
        Object value = execution.getVariable(variable);

        return value == null ? List.of() : (List<String>) value;
    }
}
