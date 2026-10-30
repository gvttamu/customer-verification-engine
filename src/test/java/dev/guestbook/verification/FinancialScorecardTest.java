package dev.guestbook.verification;

import static org.assertj.core.api.Assertions.assertThat;

import dev.guestbook.verification.FinancialScorecard.LoanRepayment;
import dev.guestbook.verification.FinancialScorecard.Score;
import dev.guestbook.verification.LaravelClient.Financials;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinancialScorecardTest {

    @Test
    void lowDebtAndPlentyLeftOverIsLowRisk() {
        Score score = FinancialScorecard.score(financials("4200", null, "1350.50", "300", null, "900"), null);

        assertThat(score.score()).isEqualTo(85);
        assertThat(score.riskBand()).isEqualTo("low");
        assertThat(score.debtToIncome()).isEqualTo(0.39);
        assertThat(score.disposableIncome()).isEqualTo(1649.5);
        assertThat(score.reasons()).containsExactly(
                "Rent, car and loan payments take 39% of income",
                "39% of income is left after all outgoings");
    }

    @Test
    void theNewLoansRepaymentCountsAsADebtPaymentAndAnOutgoing() {
        LoanRepayment loan = FinancialScorecard.loanRepayment(new BigDecimal("15000"), 48, new BigDecimal("0.12"));

        Score score = FinancialScorecard.score(financials("4200", null, "1350.50", "300", null, "900"), loan);

        assertThat(score.score()).isEqualTo(55);
        assertThat(score.riskBand()).isEqualTo("medium");
        assertThat(score.debtToIncome()).isEqualTo(0.49);
        assertThat(score.disposableIncome()).isEqualTo(1254.49);
        assertThat(score.reasons()).containsExactly(
                "The 15000.00 loan over 48 months adds about 395.01 a month at 12% a year",
                "Rent, car and loan payments, with the new loan, take 49% of income",
                "30% of income is left after all outgoings");
    }

    @Test
    void estimatesTheRepaymentOnAnAmortisingLoan() {
        assertThat(FinancialScorecard.loanRepayment(new BigDecimal("15000"), 48, new BigDecimal("0.12")).monthly())
                .isEqualByComparingTo("395.01");
        assertThat(FinancialScorecard.loanRepayment(new BigDecimal("1200"), 12, BigDecimal.ZERO).monthly())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void thereIsNoRepaymentWithoutAnAmountAndTerm() {
        assertThat(FinancialScorecard.loanRepayment(null, 48, new BigDecimal("0.12"))).isNull();
        assertThat(FinancialScorecard.loanRepayment(new BigDecimal("15000"), null, new BigDecimal("0.12"))).isNull();
    }

    @Test
    void aFractionalRateIsShownWithItsDecimals() {
        LoanRepayment loan = FinancialScorecard.loanRepayment(new BigDecimal("10000"), 24, new BigDecimal("0.125"));

        assertThat(FinancialScorecard.score(financials("4000", null, null, null, null, null), loan).reasons().get(0))
                .endsWith("at 12.5% a year");
    }

    @Test
    void otherIncomeCountsTowardsIncome() {
        Score score = FinancialScorecard.score(financials("3000", "500", "1000", "300", null, "1700"), null);

        assertThat(score.debtToIncome()).isEqualTo(0.37);
        assertThat(score.score()).isEqualTo(60);
        assertThat(score.riskBand()).isEqualTo("medium");
    }

    @Test
    void heavyDebtWithLittleLeftOverIsHighRisk() {
        Score score = FinancialScorecard.score(financials("3000", null, "1200", "400", "200", "1000"), null);

        assertThat(score.score()).isEqualTo(5);
        assertThat(score.riskBand()).isEqualTo("high");
    }

    @Test
    void saysHowFarOutgoingsExceedIncome() {
        Score score = FinancialScorecard.score(financials("2000", null, "1500", null, null, "800"), null);

        assertThat(score.score()).isZero();
        assertThat(score.disposableIncome()).isEqualTo(-300.0);
        assertThat(score.reasons()).contains("Outgoings exceed income by 300.00 a month");
    }

    @Test
    void noIncomeScoresZeroWithoutARatio() {
        Score score = FinancialScorecard.score(financials(null, null, "900", null, null, null), null);

        assertThat(score.score()).isZero();
        assertThat(score.riskBand()).isEqualTo("high");
        assertThat(score.debtToIncome()).isNull();
        assertThat(score.reasons()).containsExactly("No income recorded");
    }

    @Test
    void bandAndPointBoundariesAreInclusive() {
        assertThat(FinancialScorecard.debtToIncomePoints(0.30)).isEqualTo(60);
        assertThat(FinancialScorecard.debtToIncomePoints(0.3001)).isEqualTo(45);
        assertThat(FinancialScorecard.disposableIncomePoints(0.30)).isEqualTo(40);
        assertThat(FinancialScorecard.disposableIncomePoints(0)).isZero();
        assertThat(FinancialScorecard.riskBand(75)).isEqualTo("low");
        assertThat(FinancialScorecard.riskBand(74)).isEqualTo("medium");
        assertThat(FinancialScorecard.riskBand(50)).isEqualTo("medium");
        assertThat(FinancialScorecard.riskBand(49)).isEqualTo("high");
    }

    static Financials financials(String income, String otherIncome, String rent, String car, String otherDebt, String living) {
        return new Financials(amount(income), amount(otherIncome), amount(rent), amount(car), amount(otherDebt), amount(living));
    }

    private static BigDecimal amount(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
