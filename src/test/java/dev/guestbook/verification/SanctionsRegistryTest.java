package dev.guestbook.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SanctionsRegistryTest {

    private SanctionsRegistry sanctions;

    @BeforeEach
    void loadSanctionsList() throws IOException {
        sanctions = new SanctionsRegistry(new ObjectMapper());
    }

    @Test
    void matchesSanctionedPhoneNumbersWhateverTheFormatting() {
        assertThat(sanctions.screenContact("+1 (555) 010-0666", null))
                .containsExactly("Phone number +1 (555) 010-0666 is on the Demo sanctions list");
    }

    @Test
    void matchesSanctionedEmailsIgnoringCase() {
        assertThat(sanctions.screenContact(null, " Blocked@Example.com"))
                .containsExactly("Email address  Blocked@Example.com is on the Demo sanctions list");
    }

    @Test
    void reportsBothContactMatches() {
        assertThat(sanctions.screenContact("+15550100666", "blocked@example.com")).hasSize(2);
    }

    @Test
    void clearsUnlistedContactDetails() {
        assertThat(sanctions.screenContact("+1 555 010 0667", "ada@example.com")).isEmpty();
    }

    @Test
    void matchesSanctionedAddressesDespiteAbbreviationsCaseAndPunctuation() {
        assertThat(sanctions.screenAddress("666 shadow ln.", "66666", "united states"))
                .containsExactly("Address 666 shadow ln., 66666, united states is on the Demo sanctions list");
        assertThat(sanctions.screenAddress("13 Blackwater Rd", "ec1a1bb", "United Kingdom")).hasSize(1);
    }

    @Test
    void clearsAnAddressThatOnlyPartlyMatches() {
        assertThat(sanctions.screenAddress("666 Shadow Lane", "66667", "United States")).isEmpty();
        assertThat(sanctions.screenAddress("667 Shadow Lane", "66666", "United States")).isEmpty();
    }
}
