package dev.guestbook.verification;

import dev.guestbook.verification.LaravelClient.Financials;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Scores how well a customer can afford their commitments, including the repayment on
 * the loan they're applying for, from 0 (can't) to 100.
 *
 * <ul>
 *   <li>Up to 60 points for the debt-to-income ratio: rent or mortgage, car payments, other
 *       loan payments and the new loan's repayment as a share of total income. 30% or less
 *       earns full points.</li>
 *   <li>Up to 40 points for what's left after every outgoing, living expenses and the new
 *       repayment included, as a share of income. 30% or more earns full points.</li>
 * </ul>
 *
 * 75 or more is low risk, 50 to 74 medium, and below 50 high. A blank amount counts as zero.
 */
public final class FinancialScorecard {

    private FinancialScorecard() {
    }

    /**
     * @param debtToIncome null when the customer has no income, so there's no ratio
     */
    public record Score(int score, String riskBand, Double debtToIncome, double disposableIncome, List<String> reasons) {
    }

    /**
     * The loan applied for and its estimated monthly repayment, or null for a customer
     * who hasn't applied for one.
     */
    public record LoanRepayment(BigDecimal amount, int termMonths, BigDecimal annualInterestRate, BigDecimal monthly) {
    }

    /**
     * Estimates the monthly repayment on an amortising loan at a fixed rate. Null when the
     * amount or term is missing.
     */
    public static LoanRepayment loanRepayment(BigDecimal amount, Integer termMonths, BigDecimal annualInterestRate) {
        if (amount == null || termMonths == null || termMonths <= 0 || amount.signum() <= 0) {
            return null;
        }

        BigDecimal monthlyRate = annualInterestRate.divide(BigDecimal.valueOf(12), MathContext.DECIMAL64);
        BigDecimal monthly = monthlyRate.signum() == 0
                ? amount.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP)
                : amount.multiply(monthlyRate).divide(
                        BigDecimal.ONE.subtract(BigDecimal.ONE.add(monthlyRate).pow(-termMonths, MathContext.DECIMAL64)),
                        2, RoundingMode.HALF_UP);

        return new LoanRepayment(amount, termMonths, annualInterestRate, monthly);
    }

    public static Score score(Financials financials, LoanRepayment loan) {
        BigDecimal newRepayment = loan == null ? BigDecimal.ZERO : loan.monthly();
        BigDecimal income = sum(financials.monthlyIncome(), financials.otherIncome());
        BigDecimal debtPayments = sum(financials.rentOrMortgage(), financials.carPayments(), financials.otherDebtPayments(), newRepayment);
        BigDecimal disposable = income.subtract(debtPayments).subtract(orZero(financials.livingExpenses()));

        List<String> reasons = new ArrayList<>();

        if (loan != null) {
            reasons.add("The " + money(loan.amount()) + " loan over " + loan.termMonths() + " months adds about "
                    + money(loan.monthly()) + " a month at " + ratePercent(loan.annualInterestRate()) + " a year");
        }

        if (income.signum() <= 0) {
            reasons.add("No income recorded");

            return new Score(0, "high", null, disposable.doubleValue(), List.copyOf(reasons));
        }

        double debtToIncome = debtPayments.divide(income, 4, RoundingMode.HALF_UP).doubleValue();
        double disposableShare = disposable.divide(income, 4, RoundingMode.HALF_UP).doubleValue();

        int score = debtToIncomePoints(debtToIncome) + disposableIncomePoints(disposableShare);

        reasons.add((loan == null ? "Rent, car and loan payments" : "Rent, car and loan payments, with the new loan,")
                + " take " + percent(debtToIncome) + " of income");
        reasons.add(disposable.signum() < 0
                ? "Outgoings exceed income by " + money(disposable.negate()) + " a month"
                : percent(disposableShare) + " of income is left after all outgoings");

        return new Score(score, riskBand(score), Math.round(debtToIncome * 100) / 100.0,
                disposable.setScale(2, RoundingMode.HALF_UP).doubleValue(), List.copyOf(reasons));
    }

    static int debtToIncomePoints(double debtToIncome) {
        if (debtToIncome <= 0.30) {
            return 60;
        }
        if (debtToIncome <= 0.40) {
            return 45;
        }
        if (debtToIncome <= 0.50) {
            return 25;
        }
        return 0;
    }

    static int disposableIncomePoints(double disposableShare) {
        if (disposableShare >= 0.30) {
            return 40;
        }
        if (disposableShare >= 0.20) {
            return 30;
        }
        if (disposableShare >= 0.10) {
            return 15;
        }
        return disposableShare > 0 ? 5 : 0;
    }

    static String riskBand(int score) {
        if (score >= 75) {
            return "low";
        }
        return score >= 50 ? "medium" : "high";
    }

    private static String percent(double share) {
        return Math.round(share * 100) + "%";
    }

    /** An interest rate keeps its decimals, so 12.5% isn't shown as 13%. */
    private static String ratePercent(BigDecimal annualInterestRate) {
        return annualInterestRate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal sum(BigDecimal... amounts) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(orZero(amount));
        }
        return total;
    }

    private static BigDecimal orZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
