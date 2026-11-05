package poc.doris.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.domain.DorisSyncOutbox;
import poc.doris.console.domain.DorisSyncOutboxRepository;
import poc.doris.console.domain.WorkspaceDoris;
import poc.doris.console.service.CredentialCipher;
import poc.doris.console.service.PermissionService;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PermissionServiceTest {

    // Seeded by the 3-demo-data changeset
    static final String FAB_A_HOST = "7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0a01";
    static final String FAB_B_HOST = "7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0b01";

    @Autowired PermissionService service;
    @Autowired CredentialCipher cipher;
    @Autowired DorisSyncOutboxRepository outbox;
    @Autowired DorisClusterSyncRepository syncs;

    @Test
    void openPathCreatesSaWithEncryptedPasswordAndOutboxEvent() {
        WorkspaceDoris path = service.openPath("sales-analytics", "fab-a", FAB_A_HOST);

        assertThat(path.getUsername()).isEqualTo("sa_sales_analytics");
        assertThat(path.getKeyVersion()).isEqualTo(1);
        assertThat(cipher.decrypt(path.getPasswordEnc())).hasSize(32).isNotEqualTo(path.getPasswordEnc());
        assertThat(outbox.findTop50ByOrderByIdDesc()).singleElement().satisfies(e -> {
            assertThat(e.getClusterName()).isEqualTo("fab-a");
            assertThat(e.getDesiredVersion()).isEqualTo(1);
            assertThat(e.getStatus()).isEqualTo(DorisSyncOutbox.PENDING);
        });
    }

    @Test
    void openPathRejectsHostFromAnotherFab() {
        assertStatus(() -> service.openPath("sales-analytics", "fab-a", FAB_B_HOST), HttpStatus.BAD_REQUEST);
    }

    @Test
    void grantWithoutDorisPathIsForbidden() {
        assertStatus(() -> service.addGrant("logistics", "fab-a", "internal", "olist", "orders", "SELECT_PRIV"),
                HttpStatus.FORBIDDEN);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void pathWithGrantsCannotBeCleared() {
        service.openPath("logistics", "fab-b", FAB_B_HOST);
        long grantId = service.addGrant("logistics", "fab-b", "internal", "olist", "orders", "SELECT_PRIV").getId();

        assertStatus(() -> service.clearPath("logistics", "fab-b"), HttpStatus.CONFLICT);

        service.removeGrant("logistics", "fab-b", grantId);
        service.clearPath("logistics", "fab-b");
    }

    @Test
    void rejectsDuplicateGrantAndUnsafeNames() {
        service.openPath("marketing", "fab-a", FAB_A_HOST);
        service.addGrant("marketing", "fab-a", "internal", "olist", "*", "SELECT_PRIV");

        assertStatus(() -> service.addGrant("marketing", "fab-a", "internal", "olist", "*", "SELECT_PRIV"),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.addGrant("marketing", "fab-a", "internal", "olist`; DROP", "x", "SELECT_PRIV"),
                HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.addGrant("marketing", "fab-a", "internal", "olist", "orders", "ADMIN_PRIV"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void everyChangeBumpsOnlyItsOwnFabVersion() {
        service.openPath("marketing", "fab-a", FAB_A_HOST);
        service.addGrant("marketing", "fab-a", "internal", "olist", "orders", "SELECT_PRIV");
        service.addGrant("marketing", "fab-a", "internal", "olist", "customers", "SELECT_PRIV");

        assertThat(syncs.findById("fab-a").orElseThrow().getDesiredVersion()).isEqualTo(3);
        assertThat(syncs.findById("fab-b").orElseThrow().getDesiredVersion()).isZero();
        assertThat(outbox.findTop50ByOrderByIdDesc()).extracting(DorisSyncOutbox::getDesiredVersion)
                .containsExactly(3L, 2L, 1L);
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
