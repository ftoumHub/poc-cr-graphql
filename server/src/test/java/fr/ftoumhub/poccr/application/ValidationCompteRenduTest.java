package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.application.GestionDocumentsTest.DocumentsEnMemoire;
import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.Telephone;
import fr.ftoumhub.poccr.domaine.professionnel.TypeMedecin;
import fr.ftoumhub.poccr.domaine.validation.Empreinte;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;
import fr.ftoumhub.poccr.domaine.validation.ValidationEnCours;
import fr.ftoumhub.poccr.domaine.validation.VariablesDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidationCompteRenduTest {

    private static final NomDocument CR = new NomDocument("cr-martin");
    private static final UUID DOCUMENT = UUID.randomUUID();
    private static final UUID MARTIN = UUID.randomUUID();
    private static final UUID MARTINEZ = UUID.randomUUID();
    private static final String DONNEES = "{\"patient\":{\"c0\":\"MARTIN\"}}";
    private static final Instant MAINTENANT = Instant.parse("2026-11-07T21:00:00Z");
    private static final ProfessionnelSante PETIT = ProfessionnelSante.builder().id(UUID.randomUUID()).code("PETIT")
            .titre("Pr").prenom("Anne").nom("PETIT").type(TypeMedecin.ETABLISSEMENT).construire();
    private static final ProfessionnelSante DURAND = ProfessionnelSante.builder().id(UUID.randomUUID())
            .code("DURAND").titre("Dr").nom("DURAND").type(TypeMedecin.ETABLISSEMENT).construire();

    private final ArchiveEnMemoire archive = new ArchiveEnMemoire();
    private final DocumentsEnMemoire documents = new DocumentsEnMemoire();
    private final List<Execution> executions = new ArrayList<>();
    private final ExecuteurRequetesDossier executeur = (requete, variables, enCours) -> {
        executions.add(new Execution(requete, variables, enCours));
        return DONNEES;
    };
    private final ValidationCompteRendu validation = new ValidationCompteRendu(executeur, archive, documents,
            new ProfessionnelsEnMemoire(PETIT, DURAND), Clock.fixed(MAINTENANT, ZoneOffset.UTC));

    record Execution(String requete, VariablesDocument variables, ValidationEnCours enCours) {
    }

    @BeforeEach
    void documentPretAValider() {
        documents.enregistrer(Document.builder().id(DOCUMENT).nom(CR).patientId(MARTIN)
                .etablissementId(UUID.randomUUID()).creeLe(MAINTENANT).construire());
        documents.remplacerActeurs(DOCUMENT,
                ActeursDocument.saisis(List.of(PETIT.id(), DURAND.id()), null, List.of(), List.of(), false));
    }

    @Test
    void valider_fige_les_donnees_produites_par_le_serveur_et_leur_empreinte() {
        Validation v = validation.valider(CR, MARTIN, "query DonneesDocument { … }");

        assertThat(v.donneesJson()).isEqualTo(DONNEES);
        assertThat(v.empreinteDonnees()).isEqualTo(Empreinte.sha256(DONNEES.getBytes(StandardCharsets.UTF_8)));
        assertThat(v.valideLe()).isEqualTo(MAINTENANT);
        assertThat(v.documentId()).isEqualTo(DOCUMENT);
        assertThat(v.estScelle()).isFalse();
        assertThat(validation.etat(CR)).contains(v);
    }

    @Test
    void le_valideur_est_le_signataire_principal() {
        Validation v = validation.valider(CR, MARTIN, "q");

        assertThat(v.valideurId()).isEqualTo(PETIT.id());
        assertThat(v.validePar()).isEqualTo("Pr Anne PETIT");
    }

    @Test
    void la_requete_s_execute_sur_le_document_valide_avec_la_date_et_le_valideur_definitifs() {
        Validation v = validation.valider(CR, MARTIN, "q");

        assertThat(executions).singleElement().satisfies(e -> {
            assertThat(e.variables()).isEqualTo(new VariablesDocument(DOCUMENT, MARTIN));
            assertThat(e.enCours()).isEqualTo(new ValidationEnCours(DOCUMENT, v.valideLe(), PETIT.id()));
        });
    }

    @Test
    void un_compte_rendu_ne_se_valide_pas_sans_signataire_principal() {
        documents.remplacerActeurs(DOCUMENT, ActeursDocument.AUCUN);

        assertThatThrownBy(() -> validation.valider(CR, MARTIN, "q"))
                .isInstanceOf(ValidationCompteRendu.ValidationImpossible.class)
                .hasMessageContaining("signataire principal");
        assertThat(executions).isEmpty();
        assertThat(archive.deDocument(CR)).isEmpty();
    }

    @Test
    void la_validation_refuse_un_autre_patient_que_celui_du_document() {
        assertThatThrownBy(() -> validation.valider(CR, MARTINEZ, "q"))
                .isInstanceOf(ValidationCompteRendu.ValidationImpossible.class)
                .hasMessageContaining("autre patient");
        assertThat(executions).isEmpty();
    }

    @Test
    void un_compte_rendu_ne_se_valide_pas_sans_patient() {
        assertThatThrownBy(() -> validation.valider(CR, null, "q"))
                .isInstanceOf(ValidationCompteRendu.ValidationImpossible.class);
    }

    @Test
    void la_validation_rattache_le_patient_d_un_document_qui_n_en_avait_pas() {
        documents.enregistrer(documents.parNom(CR).orElseThrow().avecPatient(null));

        validation.valider(CR, MARTINEZ, "q");

        assertThat(documents.parNom(CR)).get().extracting(Document::patientId).isEqualTo(MARTINEZ);
    }

    @Test
    void un_document_sans_fiche_ne_se_valide_pas() {
        assertThatThrownBy(() -> validation.valider(new NomDocument("inconnu"), MARTIN, "q"))
                .isInstanceOf(DocumentIntrouvable.class);
    }

    @Test
    void un_compte_rendu_ne_se_valide_qu_une_fois() {
        validation.valider(CR, MARTIN, "q");

        assertThatThrownBy(() -> validation.valider(CR, MARTIN, "q"))
                .isInstanceOf(ValidationCompteRendu.CompteRenduDejaValide.class);
    }

    @Test
    void une_requete_rejetee_ne_laisse_aucune_trace() {
        documents.enregistrer(documents.parNom(CR).orElseThrow().avecPatient(null));
        ValidationCompteRendu enErreur = new ValidationCompteRendu(
                (requete, variables, enCours) -> {
                    throw new ExecuteurRequetesDossier.RequeteRejetee("patient introuvable");
                },
                archive, documents, new ProfessionnelsEnMemoire(PETIT), Clock.systemUTC());

        assertThatThrownBy(() -> enErreur.valider(CR, MARTIN, "q"))
                .isInstanceOf(ExecuteurRequetesDossier.RequeteRejetee.class);
        assertThat(archive.deDocument(CR)).isEmpty();
        assertThat(documents.parNom(CR)).get().extracting(Document::patientId).isNull();
    }

    @Test
    void un_document_non_valide_se_sauvegarde_librement() {
        assertThat(validation.autoriserSauvegarde(CR, new byte[]{1})).isTrue();
        assertThat(validation.autoriserSauvegarde(CR, new byte[]{2})).isTrue();
        assertThat(archive.deDocument(CR)).isEmpty();
    }

    @Test
    void la_premiere_sauvegarde_apres_validation_scelle_le_docx_et_les_suivantes_sont_refusees() {
        validation.valider(CR, MARTIN, "q");
        byte[] docxFinal = {1, 2, 3};

        assertThat(validation.autoriserSauvegarde(CR, docxFinal)).isTrue();
        assertThat(archive.deDocument(CR)).get()
                .satisfies(v -> {
                    assertThat(v.empreinteDocx()).isEqualTo(Empreinte.sha256(docxFinal));
                    assertThat(v.valideurId()).isEqualTo(PETIT.id());
                });

        assertThat(validation.autoriserSauvegarde(CR, new byte[]{9})).isFalse();
        assertThat(archive.deDocument(CR).get().empreinteDocx()).isEqualTo(Empreinte.sha256(docxFinal));
    }

    static final class ProfessionnelsEnMemoire implements ProfessionnelsSante {
        private final Map<UUID, ProfessionnelSante> parId;

        ProfessionnelsEnMemoire(ProfessionnelSante... professionnels) {
            parId = List.of(professionnels).stream().collect(Collectors.toMap(ProfessionnelSante::id, p -> p));
        }

        @Override
        public Map<UUID, ProfessionnelSante> parIds(Set<UUID> ids) {
            return parId.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override public List<ProfessionnelSante> rechercher(String texte, int limite) { return List.of(); }
        @Override public Map<UUID, List<Adresse>> adressesDe(Set<UUID> ids) { return Map.of(); }
        @Override public Map<UUID, List<Telephone>> telephonesDe(Set<UUID> ids) { return Map.of(); }
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
