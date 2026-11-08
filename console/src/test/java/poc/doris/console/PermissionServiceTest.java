package poc.doris.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import poc.doris.console.domain.DorisClusterSyncRepository;
import poc.doris.console.domain.DorisSaGrantRepository;
import poc.doris.console.domain.DorisSyncOutbox;
import poc.doris.console.domain.DorisSyncOutboxRepository;
import poc.doris.console.service.CredentialCipher;
import poc.doris.console.service.PermissionService;
import poc.doris.console.service.PermissionService.NewPassword;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PermissionServiceTest {

    // Seeded by the 3-demo-data changeset
    static final String FAB_A_HOST = "7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0a01";
    static final String FAB_B_HOST = "7d1f3a52-0a5e-4c1a-9a43-6f2b1c0e0b01";

    @MockitoBean RedissonClient redis; // scheduler locks only; jobs are off in tests
    @Autowired PermissionService service;
    @Autowired CredentialCipher cipher;
    @Autowired DorisSyncOutboxRepository outbox;
    @Autowired DorisClusterSyncRepository syncs;
    @Autowired DorisSaGrantRepository grants;

    @Test
    void createSaGeneratesAPasswordThatIsOnlyStoredEncrypted() {
        service.openPath("workspace-a", "fab-a", FAB_A_HOST);
        NewPassword created = service.createSa("workspace-a", "fab-a", "sa_workspace_a_etl", "每日 ETL");

        assertThat(created.password()).hasSize(32);
        assertThat(created.sa().getPasswordEnc()).isNotEqualTo(created.password());
        assertThat(cipher.decrypt(created.sa().getPasswordEnc())).isEqualTo(created.password());
        assertThat(created.sa().getDescription()).isEqualTo("每日 ETL");
        assertThat(outbox.findTop50ByOrderByIdDesc()).extracting(DorisSyncOutbox::getDesiredVersion)
                .containsExactly(2L, 1L);
    }

    @Test
    void aWorkspaceCanHaveSeveralSasButUsernamesAreUniquePerDorisHost() {
        service.openPath("workspace-a", "fab-a", FAB_A_HOST);
        service.openPath("workspace-b", "fab-a", FAB_A_HOST);
        service.createSa("workspace-a", "fab-a", "sa_etl", "");
        service.createSa("workspace-a", "fab-a", "sa_bi", "");

        assertStatus(() -> service.createSa("workspace-b", "fab-a", "sa_etl", ""), HttpStatus.CONFLICT);
        assertStatus(() -> service.createSa("workspace-a", "fab-a", "root", ""), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.createSa("workspace-a", "fab-a", "sa-etl'; DROP", ""), HttpStatus.BAD_REQUEST);
        // Another Doris host is another user namespace
        service.openPath("workspace-b", "fab-b", FAB_B_HOST);
        service.createSa("workspace-b", "fab-b", "sa_etl", "");
    }

    @Test
    void regeneratingReplacesThePasswordAndQueuesASync() {
        service.openPath("workspace-a", "fab-a", FAB_A_HOST);
        NewPassword created = service.createSa("workspace-a", "fab-a", "sa_a", "");
        String before = created.sa().getPasswordEnc();

        NewPassword renewed = service.regeneratePassword("workspace-a", "fab-a", created.sa().getId());

        assertThat(renewed.password()).isNotEqualTo(created.password());
        assertThat(cipher.decrypt(renewed.sa().getPasswordEnc())).isEqualTo(renewed.password());
        assertThat(renewed.sa().getPasswordEnc()).isNotEqualTo(before);
        assertThat(syncs.findById("fab-a").orElseThrow().getDesiredVersion()).isEqualTo(3);
    }

    @Test
    void openPathRejectsHostFromAnotherFab() {
        assertStatus(() -> service.openPath("workspace-a", "fab-a", FAB_B_HOST), HttpStatus.BAD_REQUEST);
    }

    @Test
    void writesWithoutDorisPathAreForbidden() {
        assertStatus(() -> service.createSa("workspace-b", "fab-a", "sa_b", ""), HttpStatus.FORBIDDEN);
        assertStatus(() -> service.addGrant("workspace-b", "fab-a", "any", "internal", "olist", "orders",
                "SELECT_PRIV"), HttpStatus.FORBIDDEN);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void saWithGrantsCannotBeDeletedAndPathWithSasCannotBeCleared() {
        service.openPath("workspace-b", "fab-b", FAB_B_HOST);
        String sa = service.createSa("workspace-b", "fab-b", "sa_b", "").sa().getId();
        long grantId = service.addGrant("workspace-b", "fab-b", sa, "internal", "olist", "orders", "SELECT_PRIV")
                .getId();

        assertStatus(() -> service.deleteSa("workspace-b", "fab-b", sa), HttpStatus.CONFLICT);
        assertStatus(() -> service.clearPath("workspace-b", "fab-b"), HttpStatus.CONFLICT);

        service.removeGrant("workspace-b", "fab-b", grantId);
        service.deleteSa("workspace-b", "fab-b", sa);
        service.clearPath("workspace-b", "fab-b");
    }

    @Test
    void rejectsDuplicateGrantAndUnsafeNames() {
        service.openPath("workspace-c", "fab-a", FAB_A_HOST);
        String sa = service.createSa("workspace-c", "fab-a", "sa_c", "").sa().getId();
        service.addGrant("workspace-c", "fab-a", sa, "internal", "olist", "*", "SELECT_PRIV");

        assertStatus(() -> service.addGrant("workspace-c", "fab-a", sa, "internal", "olist", "*", "SELECT_PRIV"),
                HttpStatus.CONFLICT);
        assertStatus(() -> service.addGrant("workspace-c", "fab-a", sa, "internal", "olist`; DROP", "x",
                "SELECT_PRIV"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.addGrant("workspace-c", "fab-a", sa, "internal", "olist", "orders", "ADMIN_PRIV"),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void anSaOfAnotherWorkspaceIsNotFound() {
        service.openPath("workspace-a", "fab-a", FAB_A_HOST);
        service.openPath("workspace-b", "fab-a", FAB_A_HOST);
        String other = service.createSa("workspace-b", "fab-a", "sa_b", "").sa().getId();

        assertStatus(() -> service.addGrant("workspace-a", "fab-a", other, "internal", "olist", "orders",
                "SELECT_PRIV"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.regeneratePassword("workspace-a", "fab-a", other), HttpStatus.NOT_FOUND);
    }

    @Test
    void everyChangeBumpsOnlyItsOwnFabVersion() {
        service.openPath("workspace-c", "fab-a", FAB_A_HOST);
        String sa = service.createSa("workspace-c", "fab-a", "sa_c", "").sa().getId();
        service.addGrant("workspace-c", "fab-a", sa, "internal", "olist", "orders", "SELECT_PRIV");

        assertThat(syncs.findById("fab-a").orElseThrow().getDesiredVersion()).isEqualTo(3);
        assertThat(syncs.findById("fab-b").orElseThrow().getDesiredVersion()).isZero();
        assertThat(outbox.findTop50ByOrderByIdDesc()).extracting(DorisSyncOutbox::getDesiredVersion)
                .containsExactly(3L, 2L, 1L);
    }

    @Test
    void copyGrantsAddsOnlyMissingGrantsAndBumpsOnlyChangedFabs() {
        service.openPath("workspace-a", "fab-a", FAB_A_HOST);
        service.openPath("workspace-a", "fab-b", FAB_B_HOST);
        String from = service.createSa("workspace-a", "fab-a", "sa_a", "").sa().getId();
        String to = service.createSa("workspace-a", "fab-b", "sa_a", "").sa().getId();
        service.addGrant("workspace-a", "fab-a", from, "internal", "olist", "orders", "SELECT_PRIV");
        service.addGrant("workspace-a", "fab-a", from, "internal", "olist", "customers", "SELECT_PRIV");
        service.addGrant("workspace-a", "fab-b", to, "internal", "olist", "orders", "SELECT_PRIV");
        service.addGrant("workspace-a", "fab-b", to, "internal", "olist", "sellers", "LOAD_PRIV");

        assertThat(service.copyGrants("workspace-a", "fab-a", from, List.of(to))).isEqualTo(1);
        assertThat(grants.countBySaId(to)).isEqualTo(3);
        assertThat(syncs.findById("fab-b").orElseThrow().getDesiredVersion()).isEqualTo(5);

        // Nothing left to copy: no new grants and no new sync event
        assertThat(service.copyGrants("workspace-a", "fab-a", from, List.of(to))).isZero();
        assertThat(syncs.findById("fab-b").orElseThrow().getDesiredVersion()).isEqualTo(5);
        assertThat(syncs.findById("fab-a").orElseThrow().getDesiredVersion()).isEqualTo(4);
    }

    @Test
    void copyGrantsOnlyToSasOfTheSameWorkspace() {
        service.openPath("workspace-a", "fab-a", FAB_A_HOST);
        service.openPath("workspace-b", "fab-a", FAB_A_HOST);
        String from = service.createSa("workspace-a", "fab-a", "sa_a", "").sa().getId();
        String other = service.createSa("workspace-b", "fab-a", "sa_b", "").sa().getId();
        service.addGrant("workspace-a", "fab-a", from, "internal", "olist", "orders", "SELECT_PRIV");

        assertStatus(() -> service.copyGrants("workspace-a", "fab-a", from, List.of(other)), HttpStatus.BAD_REQUEST);
        assertStatus(() -> service.copyGrants("workspace-a", "fab-a", from, List.of(from)), HttpStatus.BAD_REQUEST);
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
