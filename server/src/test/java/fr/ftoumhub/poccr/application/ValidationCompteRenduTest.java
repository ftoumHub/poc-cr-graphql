package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import fr.ftoumhub.poccr.domaine.validation.Empreinte;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationCompteRenduTest {

    private static final NomDocument CR = new NomDocument("cr-martin");
    private static final UUID PATIENT = UUID.randomUUID();
    private static final String DONNEES = "{\"patient\":{\"c0\":\"MARTIN\"}}";
    private static final Instant MAINTENANT = Instant.parse("2026-11-07T21:00:00Z");

    private final ArchiveEnMemoire archive = new ArchiveEnMemoire();
    private final ValidationCompteRendu validation = new ValidationCompteRendu(
            (requete, patientId) -> DONNEES, archive, Clock.fixed(MAINTENANT, ZoneOffset.UTC));

    @Test
    void valider_fige_les_donnees_produites_par_le_serveur_et_leur_empreinte() {
        Validation v = validation.valider(CR, PATIENT, "query { … }", "Dr POC");

        assertThat(v.donneesJson()).isEqualTo(DONNEES);
        assertThat(v.empreinteDonnees()).isEqualTo(Empreinte.sha256(DONNEES.getBytes(StandardCharsets.UTF_8)));
        assertThat(v.valideLe()).isEqualTo(MAINTENANT);
        assertThat(v.estScelle()).isFalse();
        assertThat(validation.etat(CR)).contains(v);
    }

    @Test
    void un_compte_rendu_ne_se_valide_qu_une_fois() {
        validation.valider(CR, PATIENT, "q", "Dr POC");

        assertThatThrownBy(() -> validation.valider(CR, PATIENT, "q", "Dr POC"))
                .isInstanceOf(ValidationCompteRendu.CompteRenduDejaValide.class);
    }

    @Test
    void une_requete_rejetee_ne_laisse_aucune_trace() {
        ValidationCompteRendu enErreur = new ValidationCompteRendu(
                (requete, patientId) -> { throw new ExecuteurRequetesDossier.RequeteRejetee("patient introuvable"); },
                archive, Clock.systemUTC());

        assertThatThrownBy(() -> enErreur.valider(CR, PATIENT, "q", "Dr POC"))
                .isInstanceOf(ExecuteurRequetesDossier.RequeteRejetee.class);
        assertThat(archive.deDocument(CR)).isEmpty();
    }

    @Test
    void un_document_non_valide_se_sauvegarde_librement() {
        assertThat(validation.autoriserSauvegarde(CR, new byte[]{1})).isTrue();
        assertThat(validation.autoriserSauvegarde(CR, new byte[]{2})).isTrue();
        assertThat(archive.deDocument(CR)).isEmpty();
    }

    @Test
    void la_premiere_sauvegarde_apres_validation_scelle_le_docx_et_les_suivantes_sont_refusees() {
        validation.valider(CR, PATIENT, "q", "Dr POC");
        byte[] docxFinal = {1, 2, 3};

        assertThat(validation.autoriserSauvegarde(CR, docxFinal)).isTrue();
        assertThat(archive.deDocument(CR)).get()
                .extracting(Validation::empreinteDocx)
                .isEqualTo(Empreinte.sha256(docxFinal));

        assertThat(validation.autoriserSauvegarde(CR, new byte[]{9})).isFalse();
        assertThat(archive.deDocument(CR).get().empreinteDocx()).isEqualTo(Empreinte.sha256(docxFinal));
    }

    static final class ArchiveEnMemoire implements ArchiveValidations {
        private final Map<String, Validation> validations = new HashMap<>();

        @Override
        public Optional<Validation> deDocument(NomDocument document) {
            return Optional.ofNullable(validations.get(document.valeur()));
        }

        @Override
        public void enregistrer(Validation validation) {
            validations.put(validation.document(), validation);
        }
    }
}
