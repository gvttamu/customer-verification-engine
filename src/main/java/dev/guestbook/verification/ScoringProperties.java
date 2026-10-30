package dev.guestbook.verification;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for financial scoring. The customer app doesn't capture an interest rate, so
 * the repayment on the loan applied for is estimated at this annual rate (0.12 is 12%).
 */
@ConfigurationProperties("scoring")
public record ScoringProperties(BigDecimal annualInterestRate) {
}
