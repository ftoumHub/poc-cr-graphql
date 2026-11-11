package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.DocumentFige;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import fr.ftoumhub.poccr.domaine.port.FichiersDocuments;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GestionDocumentsTest {

    private static final NomDocument CR = new NomDocument("cr-martin");
    private static final NomDocument COPIE = new NomDocument("cr-martin-bis");
    private static final UUID MARTIN = UUID.randomUUID();
    private static final UUID MARTINEZ = UUID.randomUUID();
    private static final UUID DURAND = UUID.randomUUID();
    private static final UUID GIRARD = UUID.randomUUID();
    private static final Instant MAINTENANT = Instant.parse("2026-11-11T10:00:00Z");
    private static final Etablissement CH_POC = Etablissement.builder().id(UUID.randomUUID()).finess("990000001")
            .libelle("Centre hospitalier POC").construire();
    private static final ActeursDocument ACTEURS =
            ActeursDocument.saisis(List.of(DURAND), GIRARD, List.of(), List.of(), true);

    private final DocumentsEnMemoire documents = new DocumentsEnMemoire();
    private final FichiersEnMemoire fichiers = new FichiersEnMemoire();
    private final ValidationsEnMemoire validations = new ValidationsEnMemoire();
    private final Etablissements etablissements = new Etablissements() {
        @Override public Map<UUID, Etablissement> parIds(Set<UUID> ids) { return Map.of(CH_POC.id(), CH_POC); }
        @Override public Etablissement parDefaut() { return CH_POC; }
    };
    private final GestionDocuments gestion = new GestionDocuments(documents, fichiers, validations, etablissements,
            new ValidationCompteRenduTest.ProfessionnelsEnMemoire(ps(DURAND), ps(GIRARD)),
            Clock.fixed(MAINTENANT, ZoneOffset.UTC), UUID::randomUUID);

    private static fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante ps(UUID id) {
        return fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante.builder().id(id).code(id.toString())
                .nom("PS").type(fr.ftoumhub.poccr.domaine.professionnel.TypeMedecin.ETABLISSEMENT).construire();
    }

    // --- création -------------------------------------------------------------------------------

    @Test
    void la_creation_d_un_document_cree_sa_fiche_et_son_fichier() {
        Document cree = gestion.creer(CR, "Compte rendu d'hospitalisation", null);

        assertThat(gestion.document(CR)).contains(cree);
        assertThat(cree.etablissementId()).isEqualTo(CH_POC.id());
        assertThat(cree.creeLe()).isEqualTo(MAINTENANT);
        assertThat(cree.patientId()).isNull();
        assertThat(fichiers.noms).contains(CR);
    }

    @Test
    void un_echec_du_fichier_annule_la_fiche() {
        fichiers.enPanne = true;

        assertThatThrownBy(() -> gestion.creer(CR, null, null)).isInstanceOf(IllegalStateException.class);
        assertThat(gestion.document(CR)).isEmpty();
    }

    @Test
    void un_nom_deja_pris_est_refuse() {
        gestion.creer(CR, null, null);

        assertThatThrownBy(() -> gestion.creer(CR, null, null)).hasMessageContaining("existe déjà");
    }

    // --- renommage ------------------------------------------------------------------------------

    @Test
    void renommer_un_document_conserve_son_identifiant_et_ses_acteurs() {
        Document cree = gestion.creer(CR, null, null);
        gestion.definirActeurs(CR, ACTEURS);

        Document renomme = gestion.renommer(CR, COPIE);

        assertThat(renomme.id()).isEqualTo(cree.id());
        assertThat(gestion.document(CR)).isEmpty();
        assertThat(gestion.acteurs(COPIE)).isEqualTo(ACTEURS);
        assertThat(fichiers.noms).containsExactly(COPIE);
    }

    @Test
    void un_echec_du_deplacement_retablit_l_ancien_nom() {
        gestion.creer(CR, null, null);
        fichiers.enPanne = true;

        assertThatThrownBy(() -> gestion.renommer(CR, COPIE)).isInstanceOf(IllegalStateException.class);
        assertThat(gestion.document(CR)).isPresent();
        assertThat(gestion.document(COPIE)).isEmpty();
    }

    @Test
    void un_compte_rendu_valide_ne_se_renomme_pas() {
        gestion.creer(CR, null, null);
        validations.valider(CR);

        assertThatThrownBy(() -> gestion.renommer(CR, COPIE)).isInstanceOf(DocumentFige.class);
    }

    // --- duplication ----------------------------------------------------------------------------

    @Test
    void la_copie_reprend_le_patient_et_les_acteurs_mais_pas_la_validation() {
        Document original = gestion.creer(CR, "CRH", null);
        gestion.rattacherPatient(CR, MARTIN);
        gestion.definirActeurs(CR, ACTEURS);
        validations.valider(CR);

        Document copie = gestion.dupliquer(CR, COPIE, null);

        assertThat(copie.id()).isNotEqualTo(original.id());
        assertThat(copie.patientId()).isEqualTo(MARTIN);
        assertThat(copie.titre()).isEqualTo("CRH");
        assertThat(copie.creeLe()).isEqualTo(MAINTENANT);
        assertThat(gestion.acteurs(COPIE)).isEqualTo(ACTEURS);
        // la copie est un brouillon : ses acteurs restent modifiables
        gestion.definirActeurs(COPIE, ActeursDocument.AUCUN);
        assertThat(gestion.acteurs(COPIE)).isEqualTo(ActeursDocument.AUCUN);
        assertThat(gestion.acteurs(CR)).isEqualTo(ACTEURS);
    }

    @Test
    void un_echec_de_la_copie_du_fichier_ne_laisse_pas_de_fiche() {
        gestion.creer(CR, null, null);
        gestion.definirActeurs(CR, ACTEURS);
        fichiers.enPanne = true;

        assertThatThrownBy(() -> gestion.dupliquer(CR, COPIE, null)).isInstanceOf(IllegalStateException.class);
        assertThat(gestion.document(COPIE)).isEmpty();
        assertThat(documents.acteurs).hasSize(1);
    }

    @Test
    void un_document_cree_pour_un_patient_le_porte_des_sa_creation() {
        assertThat(gestion.creer(CR, null, MARTIN).patientId()).isEqualTo(MARTIN);
    }

    @Test
    void un_gabarit_s_instancie_en_le_copiant_pour_un_patient() {
        gestion.creer(CR, "Gabarit CRH", null);
        gestion.definirActeurs(CR, ACTEURS);

        Document copie = gestion.dupliquer(CR, COPIE, MARTIN);

        assertThat(copie.patientId()).isEqualTo(MARTIN);
        assertThat(gestion.document(CR)).get().extracting(Document::patientId).isNull();
        assertThat(gestion.acteurs(COPIE)).isEqualTo(ACTEURS);
    }

    @Test
    void un_document_d_un_patient_ne_se_copie_pas_pour_un_autre() {
        gestion.creer(CR, null, MARTIN);

        assertThatThrownBy(() -> gestion.dupliquer(CR, COPIE, MARTINEZ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("que pour ce patient");
        assertThat(gestion.dupliquer(CR, COPIE, MARTIN).patientId()).isEqualTo(MARTIN);
    }

    // --- patient --------------------------------------------------------------------------------

    @Test
    void un_brouillon_peut_changer_de_patient() {
        gestion.creer(CR, null, null);
        gestion.rattacherPatient(CR, MARTIN);

        assertThat(gestion.rattacherPatient(CR, MARTINEZ).patientId()).isEqualTo(MARTINEZ);
    }

    @Test
    void le_patient_d_un_compte_rendu_valide_ne_change_plus() {
        gestion.creer(CR, null, null);
        gestion.rattacherPatient(CR, MARTIN);
        validations.valider(CR);

        assertThat(gestion.rattacherPatient(CR, MARTIN).patientId()).isEqualTo(MARTIN);
        assertThatThrownBy(() -> gestion.rattacherPatient(CR, MARTINEZ))
                .isInstanceOf(DocumentFige.class)
                .hasMessageContaining("le patient");
    }

    // --- acteurs --------------------------------------------------------------------------------

    @Test
    void les_acteurs_d_un_compte_rendu_valide_sont_figes() {
        gestion.creer(CR, null, null);
        gestion.definirActeurs(CR, ACTEURS);
        validations.valider(CR);

        assertThatThrownBy(() -> gestion.definirActeurs(CR, ActeursDocument.AUCUN)).isInstanceOf(DocumentFige.class);
        assertThat(gestion.acteurs(CR)).isEqualTo(ACTEURS);
    }

    @Test
    void un_professionnel_inconnu_est_refuse() {
        gestion.creer(CR, null, null);
        UUID inconnu = UUID.randomUUID();

        assertThatThrownBy(() -> gestion.definirActeurs(CR,
                ActeursDocument.saisis(List.of(DURAND), inconnu, List.of(), List.of(), false)))
                .isInstanceOf(fr.ftoumhub.poccr.domaine.document.ActeursInvalides.class)
                .hasMessageContaining(inconnu.toString());
        assertThat(gestion.acteurs(CR)).isEqualTo(ActeursDocument.AUCUN);
    }

    @Test
    void un_document_inconnu_est_signale() {
        assertThatThrownBy(() -> gestion.acteurs(CR)).isInstanceOf(DocumentIntrouvable.class);
        assertThatThrownBy(() -> gestion.rattacherPatient(CR, MARTIN)).isInstanceOf(DocumentIntrouvable.class);
    }

    // --- recensement ----------------------------------------------------------------------------

    @Test
    void le_recensement_cree_une_fiche_pour_chaque_fichier_qui_n_en_a_pas() {
        gestion.creer(CR, null, null);
        NomDocument ancien = new NomDocument("ancien");
        fichiers.noms.add(ancien);
        fichiers.patients.put(ancien, MARTIN);

        assertThat(gestion.recenser()).isEqualTo(1);

        Document recense = gestion.document(ancien).orElseThrow();
        assertThat(recense.patientId()).isEqualTo(MARTIN);
        assertThat(recense.etablissementId()).isEqualTo(CH_POC.id());
        assertThat(recense.creeLe()).isEqualTo(FichiersEnMemoire.DATE_FICHIER);
    }

    @Test
    void le_recensement_est_idempotent() {
        fichiers.noms.add(new NomDocument("ancien"));

        assertThat(gestion.recenser()).isEqualTo(1);
        assertThat(gestion.recenser()).isZero();
    }

    // --- faux en mémoire ------------------------------------------------------------------------

    static final class DocumentsEnMemoire implements Documents {
        final Map<UUID, Document> fiches = new HashMap<>();
        final Map<UUID, ActeursDocument> acteurs = new HashMap<>();

        @Override
        public Optional<Document> parNom(NomDocument nom) {
            return fiches.values().stream().filter(d -> d.nom().equals(nom)).findFirst();
        }

        @Override
        public Map<UUID, Document> parIds(Set<UUID> ids) {
            return fiches.entrySet().stream().filter(e -> ids.contains(e.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public List<Document> tous() {
            return List.copyOf(fiches.values());
        }

        @Override
        public void enregistrer(Document document) {
            fiches.put(document.id(), document);
        }

        @Override
        public void supprimer(UUID documentId) {
            fiches.remove(documentId);
            acteurs.remove(documentId);
        }

        @Override
        public Map<UUID, ActeursDocument> acteursDe(Set<UUID> documentIds) {
            Map<UUID, ActeursDocument> resultat = new HashMap<>();
            documentIds.forEach(id -> resultat.put(id, acteurs.getOrDefault(id, ActeursDocument.AUCUN)));
            return resultat;
        }

        @Override
        public void remplacerActeurs(UUID documentId, ActeursDocument nouveaux) {
            acteurs.put(documentId, nouveaux);
        }
    }

    static final class FichiersEnMemoire implements FichiersDocuments {
        static final Instant DATE_FICHIER = Instant.parse("2026-11-01T08:00:00Z");
        final List<NomDocument> noms = new ArrayList<>();
        final Map<NomDocument, UUID> patients = new HashMap<>();
        boolean enPanne;

        private void verifierEnService() {
            if (enPanne) {
                throw new IllegalStateException("disque indisponible");
            }
        }

        @Override public List<NomDocument> lister() { return List.copyOf(noms); }

        @Override
        public void creerVierge(NomDocument nom) {
            verifierEnService();
            noms.add(nom);
        }

        @Override
        public void renommer(NomDocument ancien, NomDocument nouveau) {
            verifierEnService();
            noms.set(noms.indexOf(ancien), nouveau);
        }

        @Override
        public void dupliquer(NomDocument source, NomDocument copie) {
            verifierEnService();
            noms.add(copie);
        }

        @Override public Instant derniereModification(NomDocument nom) { return DATE_FICHIER; }

        @Override public Optional<UUID> patientLie(NomDocument nom) { return Optional.ofNullable(patients.get(nom)); }
    }

    static final class ValidationsEnMemoire implements ArchiveValidations {
        private final Set<NomDocument> valides = new HashSet<>();
        private final Map<String, Validation> enregistrees = new HashMap<>();

        void valider(NomDocument nom) {
            valides.add(nom);
        }

        @Override
        public Optional<Validation> deDocument(NomDocument document) {
            if (!valides.contains(document)) {
                return Optional.empty();
            }
            return Optional.of(enregistrees.computeIfAbsent(document.valeur(), n -> Validation.builder()
                    .document(n).patientId(MARTIN).requete("q").donneesJson("{}").valideLe(MAINTENANT)
                    .validePar("Dr DURAND").construire()));
        }

        @Override
        public void enregistrer(Validation validation) {
            enregistrees.put(validation.document(), validation);
        }
    }
}
