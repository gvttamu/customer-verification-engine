package dev.guestbook.verification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The "database" of sanctioned contact details and addresses, loaded from sanctions.json.
 * Values are normalised before comparing, so formatting differences such as spaces in
 * phone numbers, letter case, punctuation, or "Lane" versus "Ln" don't hide a match.
 */
@Component
public class SanctionsRegistry {

    private static final Map<String, String> STREET_ABBREVIATIONS = Map.of(
            "street", "st", "road", "rd", "lane", "ln", "avenue", "ave", "drive", "dr", "square", "sq");

    private final String listName;
    private final Set<String> phones;
    private final Set<String> emails;
    private final List<NormalisedAddress> addresses;

    public SanctionsRegistry(ObjectMapper objectMapper) throws IOException {
        try (InputStream json = new ClassPathResource("sanctions.json").getInputStream()) {
            SanctionsList list = objectMapper.readValue(json, SanctionsList.class);

            this.listName = list.list();
            this.phones = list.phones().stream().map(SanctionsRegistry::normalisePhone).collect(Collectors.toSet());
            this.emails = list.emails().stream().map(SanctionsRegistry::normaliseEmail).collect(Collectors.toSet());
            this.addresses = list.addresses().stream().map(NormalisedAddress::of).toList();
        }
    }

    /**
     * @return a description of each match; empty when the contact details are clear
     */
    public List<String> screenContact(String phone, String email) {
        List<String> matches = new ArrayList<>();

        if (phone != null && phones.contains(normalisePhone(phone))) {
            matches.add("Phone number " + phone + " is on the " + listName);
        }

        if (email != null && emails.contains(normaliseEmail(email))) {
            matches.add("Email address " + email + " is on the " + listName);
        }

        return matches;
    }

    /**
     * @return a description of each match; empty when the address is clear
     */
    public List<String> screenAddress(String line1, String postalCode, String country) {
        NormalisedAddress address = NormalisedAddress.of(new SanctionedAddress(line1, postalCode, country));

        return addresses.stream()
                .filter(address::equals)
                .map(match -> "Address " + line1 + ", " + postalCode + ", " + country + " is on the " + listName)
                .toList();
    }

    static String normalisePhone(String phone) {
        return phone.replaceAll("\\D", "");
    }

    static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    static String normaliseText(String text) {
        if (text == null) {
            return "";
        }

        return List.of(text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().split(" ")).stream()
                .map(word -> STREET_ABBREVIATIONS.getOrDefault(word, word))
                .collect(Collectors.joining(" "));
    }

    record SanctionsList(String list, List<String> phones, List<String> emails, List<SanctionedAddress> addresses) {
    }

    record SanctionedAddress(String line1, @JsonProperty("postal_code") String postalCode, String country) {
    }

    private record NormalisedAddress(String line1, String postalCode, String country) {

        static NormalisedAddress of(SanctionedAddress address) {
            return new NormalisedAddress(
                    normaliseText(address.line1()),
                    normaliseText(address.postalCode()).replace(" ", ""),
                    normaliseText(address.country()));
        }
    }
}
