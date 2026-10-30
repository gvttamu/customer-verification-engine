package dev.guestbook.verification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The status page is served at the root, behind the same login as the REST API.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:status-page-test;DB_CLOSE_DELAY=-1",
        "flowable.async-executor-activate=false",
        "engine.username=status-test",
        "engine.password=correct-horse",
})
class StatusPageTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void signedInUsersGetThePage() {
        ResponseEntity<String> page = rest.withBasicAuth("status-test", "correct-horse").getForEntity("/", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("<title>Verification engine</title>");
    }

    @Test
    void thePageNeedsTheEngineLogin() {
        assertThat(rest.getForEntity("/", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.withBasicAuth("status-test", "wrong").getForEntity("/", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
