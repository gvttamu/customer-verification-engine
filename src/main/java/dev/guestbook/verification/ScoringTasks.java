package dev.guestbook.verification;

import static dev.guestbook.verification.VerificationTasks.customerId;
import static dev.guestbook.verification.VerificationTasks.runId;

import dev.guestbook.verification.FinancialScorecard.LoanRepayment;
import dev.guestbook.verification.FinancialScorecard.Score;
import dev.guestbook.verification.LaravelClient.CustomerDetails;
import dev.guestbook.verification.LaravelClient.Financials;
import dev.guestbook.verification.LaravelClient.Loan;
import dev.guestbook.verification.LaravelClient.ScoreReport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service tasks of the financial scoring process, referenced by bean name from
 * financial-scoring.bpmn20.xml. Amounts are kept as plain-text process variables,
 * so they stay exact and readable on the status page.
 */
@Configuration
public class ScoringTasks {

    @Bean
    JavaDelegate fetchCustomerFinancials(LaravelClient laravel) {
        return execution -> {
            CustomerDetails customer = laravel.fetchCustomer(customerId(execution));
            Financials financials = customer.financials() == null ? new Financials(null, null, null, null, null, null) : customer.financials();
            Loan loan = customer.loan() == null ? new Loan(null, null, null) : customer.loan();

            setAmount(execution, "monthlyIncome", financials.monthlyIncome());
            setAmount(execution, "otherIncome", financials.otherIncome());
            setAmount(execution, "rentOrMortgage", financials.rentOrMortgage());
            setAmount(execution, "carPayments", financials.carPayments());
            setAmount(execution, "otherDebtPayments", financials.otherDebtPayments());
            setAmount(execution, "livingExpenses", financials.livingExpenses());
            setAmount(execution, "loanAmount", loan.amount());
            execution.setVariable("loanTermMonths", loan.termMonths());
        };
    }

    /**
     * Scores the customer, estimating the new loan's repayment at the configured rate.
     * The rate used is kept with the run, so a later rate change doesn't hide how a score came about.
     */
    @Bean
    JavaDelegate scoreCustomerFinancials(ScoringProperties scoring) {
        return execution -> {
            LoanRepayment loan = FinancialScorecard.loanRepayment(
                    amount(execution, "loanAmount"), (Integer) execution.getVariable("loanTermMonths"), scoring.annualInterestRate());
            Score score = FinancialScorecard.score(new Financials(
                    amount(execution, "monthlyIncome"), amount(execution, "otherIncome"),
                    amount(execution, "rentOrMortgage"), amount(execution, "carPayments"),
                    amount(execution, "otherDebtPayments"), amount(execution, "livingExpenses")), loan);

            execution.setVariable("annualInterestRate", scoring.annualInterestRate().toPlainString());
            setAmount(execution, "loanRepayment", loan == null ? null : loan.monthly());
            execution.setVariable("financialScore", score.score());
            execution.setVariable("riskBand", score.riskBand());
            execution.setVariable("debtToIncome", score.debtToIncome());
            execution.setVariable("disposableIncome", score.disposableIncome());
            execution.setVariable("scoreReasons", new ArrayList<>(score.reasons()));
        };
    }

    @Bean
    @SuppressWarnings("unchecked")
    JavaDelegate reportFinancialScore(LaravelClient laravel) {
        return execution -> {
            BigDecimal loanRepayment = amount(execution, "loanRepayment");

            laravel.reportScore(customerId(execution), new ScoreReport(
                    (Integer) execution.getVariable("financialScore"),
                    (String) execution.getVariable("riskBand"),
                    (Double) execution.getVariable("debtToIncome"),
                    (Double) execution.getVariable("disposableIncome"),
                    loanRepayment == null ? null : loanRepayment.doubleValue(),
                    (List<String>) execution.getVariable("scoreReasons"),
                    execution.getProcessInstanceId(),
                    runId(execution)));
        };
    }

    private static void setAmount(DelegateExecution execution, String variable, BigDecimal amount) {
        execution.setVariable(variable, amount == null ? null : amount.toPlainString());
    }

    private static BigDecimal amount(DelegateExecution execution, String variable) {
        return execution.getVariable(variable) instanceof String amount ? new BigDecimal(amount) : null;
    }
}
