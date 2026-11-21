package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.application.ConsultationDossierClinique;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.domaine.dossier.Allergie;
import fr.ftoumhub.poccr.domaine.dossier.Antecedent;
import fr.ftoumhub.poccr.domaine.dossier.FiltreFormulaires;
import fr.ftoumhub.poccr.domaine.dossier.Formulaire;
import fr.ftoumhub.poccr.domaine.dossier.LignePrescription;
import fr.ftoumhub.poccr.domaine.dossier.NaturePrescription;
import fr.ftoumhub.poccr.domaine.dossier.ObservationMedicale;
import fr.ftoumhub.poccr.domaine.dossier.PersonneEntourage;
import fr.ftoumhub.poccr.domaine.dossier.StatutPrescription;
import fr.ftoumhub.poccr.domaine.dossier.TransmissionCiblee;
import fr.ftoumhub.poccr.domaine.patient.Ipp;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.patient.Sexe;
import fr.ftoumhub.poccr.domaine.port.DossierClinique;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tranche GraphQL du dossier clinique : contrat utilisé par les gabarits convertis (formulaires et rubriques,
 * observations par chapitre, transmissions) et regroupement des lectures par filtre.
 */
@GraphQlTest
@Import({ControleurDossierPatient.class, ControleurDossierClinique.class, GestionErreursGraphQl.class,
        ControleurDossierCliniqueTest.PortsEnMemoire.class})
class ControleurDossierCliniqueTest {

    static final UUID JEANNE = UUID.fromString("77777777-7777-7777-7777-777777777777");
    static final Instant MAINTENANT = Instant.parse("2026-11-21T09:00:00Z");

    @Autowired
    GraphQlTester graphQlTester;

    @Autowired
    Journal journal;

    @BeforeEach
    void effacerLeJournal() {
        journal.lectures.clear();
    }

    @Test
    void une_rubrique_se_lit_dans_le_dernier_formulaire_du_modele_qui_la_contient() {
        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) {
                          formulaires(contenant: "RUCVSPEC1", derniers: 1) {
                            libelle dateCreation vaccin: rubrique(code: "RUCVSPEC1") absente: rubrique(code: "INCONNUE")
                          }
                        } }""")
                .variable("id", JEANNE)
                .execute()
                .path("patient.formulaires[*].libelle").entityList(String.class).containsExactly("Carnet de vaccination")
                .path("patient.formulaires[0].dateCreation").entity(String.class).isEqualTo("2026-11-19")
                .path("patient.formulaires[0].vaccin").entity(String.class).isEqualTo("Influvac Tetra")
                .path("patient.formulaires[0].absente").valueIsNull();
    }

    @Test
    void des_liaisons_au_meme_filtre_font_une_lecture_et_des_filtres_differents_une_lecture_chacun() {
        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) {
                          c0: formulaires(contenant: "RUCVSPEC1", derniers: 1) { rubrique(code: "RUCVSPEC1") }
                          c1: formulaires(contenant: "RUCVSPEC1", derniers: 1) { rubrique(code: "RUCVNLOT1") }
                          c2: formulaires(modele: "9115405773860914995", derniers: 1) { rubrique(code: "X") }
                        } }""")
                .variable("id", JEANNE)
                .execute()
                .path("patient.c1[0].rubrique").entity(String.class).isEqualTo("L12345");

        assertThat(journal.lectures).containsExactlyInAnyOrder("formulaires:RUCVSPEC1", "formulaires:9115405773860914995");
    }

    @Test
    void une_liste_sans_filtre_se_charge_aussi_groupee() {
        // régression : un filtre nul (« derniers » absent) faisait échouer le regroupement des clés
        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) {
                          transmissionsCiblees { cible dateHeure donnees }
                          observationsMedicales(chapitre: "ide", derniers: 1) { chapitre date heure texte }
                        } }""")
                .variable("id", JEANNE)
                .execute()
                .path("patient.transmissionsCiblees[*].cible").entityList(String.class).containsExactly("Douleur")
                .path("patient.transmissionsCiblees[0].dateHeure").entity(String.class).isEqualTo("2026-11-20 08:30")
                .path("patient.observationsMedicales[*].texte").entityList(String.class).containsExactly("Nuit calme.")
                .path("patient.observationsMedicales[0].heure").entity(String.class).isEqualTo("07:15");
    }

    @Test
    void une_allergie_levee_n_apparait_que_si_on_le_demande() {
        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) {
                          actives: allergies { libelle }
                          toutes: allergies(activesSeulement: false) { libelle dateFin }
                        } }""")
                .variable("id", JEANNE)
                .execute()
                .path("patient.actives[*].libelle").entityList(String.class).containsExactly("Pénicillines")
                .path("patient.toutes[*].libelle").entityList(String.class).containsExactly("Pénicillines", "Codéine");
    }

    @Test
    void l_identite_de_naissance_et_l_ins_sont_exposes() {
        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) {
                          nom nomNaissance titre ageTexte insi { matricule natureMatricule nom prenom autresPrenoms }
                        } }""")
                .variable("id", JEANNE)
                .execute()
                .path("patient.nomNaissance").entity(String.class).isEqualTo("LEMAIRE")
                .path("patient.titre").entity(String.class).isEqualTo("Madame")
                .path("patient.ageTexte").entity(String.class).isEqualTo("84 ans")
                .path("patient.insi.natureMatricule").entity(String.class).isEqualTo("NIR")
                .path("patient.insi.prenom").entity(String.class).isEqualTo("Jeanne")
                .path("patient.insi.autresPrenoms").entity(String.class).isEqualTo("Jeanne Marie");
    }

    @SpringBootConfiguration
    static class Contexte {
    }

    static class Journal {
        final List<String> lectures = new ArrayList<>();
    }

    @TestConfiguration
    static class PortsEnMemoire {

        static final Clock HORLOGE = Clock.fixed(MAINTENANT, ZoneId.of("Europe/Paris"));

        @Bean
        Journal journal() {
            return new Journal();
        }

        @Bean
        ConsultationDossierPatient consultationDossierPatient() {
            Patient jeanne = Patient.builder().id(JEANNE).ipp(new Ipp("100007")).nom("DUBOIS").prenom("Jeanne")
                    .dateNaissance(LocalDate.of(1942, 8, 14)).sexe(Sexe.FEMININ).nomNaissance("LEMAIRE")
                    .prenomsNaissance("Jeanne Marie").nir("2420869123456").construire();
            fr.ftoumhub.poccr.domaine.port.Patients patients = new fr.ftoumhub.poccr.domaine.port.Patients() {
                public Optional<Patient> parId(UUID id) { return JEANNE.equals(id) ? Optional.of(jeanne) : Optional.empty(); }
                public List<Patient> rechercher(String texte, int limite) { return List.of(jeanne); }
                public fr.ftoumhub.poccr.domaine.ResultatPage<Patient> lister(
                        fr.ftoumhub.poccr.domaine.patient.FiltrePatients filtre, int page, int taille) {
                    return new fr.ftoumhub.poccr.domaine.ResultatPage<>(List.of(jeanne), 1);
                }
            };
            return new ConsultationDossierPatient(patients, (ids, f) -> Map.of(), ids -> Map.of(), ids -> Map.of(), HORLOGE);
        }

        @Bean
        ConsultationDossierClinique consultationDossierClinique(Journal journal) {
            return new ConsultationDossierClinique(new DossierEnMemoire(journal), HORLOGE);
        }
    }

    static class DossierEnMemoire implements DossierClinique {
        private final Journal journal;

        DossierEnMemoire(Journal journal) {
            this.journal = journal;
        }

        @Override
        public Map<UUID, List<Allergie>> allergiesDe(Set<UUID> ids) {
            return Map.of(JEANNE, List.of(
                    Allergie.builder().id(UUID.randomUUID()).patientId(JEANNE).libelle("Pénicillines").construire(),
                    Allergie.builder().id(UUID.randomUUID()).patientId(JEANNE).libelle("Codéine")
                            .dateFin(LocalDate.of(2025, 10, 1)).construire()));
        }

        @Override
        public Map<UUID, List<Antecedent>> antecedentsDe(Set<UUID> ids) {
            return Map.of();
        }

        @Override
        public Map<UUID, List<LignePrescription>> prescriptionsDe(Set<UUID> ids, NaturePrescription nature,
                                                                  StatutPrescription statut) {
            return Map.of();
        }

        @Override
        public Map<UUID, List<ObservationMedicale>> observationsMedicalesDe(Set<UUID> ids, String chapitre) {
            journal.lectures.add("observations:" + chapitre);
            return Map.of(JEANNE, List.of(
                    observation("Nuit calme.", "2026-11-21T06:15:00Z"),
                    observation("Pansement refait.", "2026-11-18T09:00:00Z")));
        }

        @Override
        public Map<UUID, List<TransmissionCiblee>> transmissionsCibleesDe(Set<UUID> ids) {
            journal.lectures.add("transmissions");
            return Map.of(JEANNE, List.of(TransmissionCiblee.builder().id(UUID.randomUUID()).patientId(JEANNE)
                    .cible("Douleur").ecriteLe(Instant.parse("2026-11-20T07:30:00Z")).donnees("EVA 5/10").construire()));
        }

        @Override
        public Map<UUID, List<PersonneEntourage>> entourageDe(Set<UUID> ids) {
            return Map.of();
        }

        @Override
        public Map<UUID, List<Formulaire>> formulairesDe(Set<UUID> ids, FiltreFormulaires filtre) {
            journal.lectures.add("formulaires:" + filtre.rubriqueAttendue().or(filtre::modeleAttendu).orElse(""));
            if (!"RUCVSPEC1".equals(filtre.contenant())) {
                return Map.of();
            }
            return Map.of(JEANNE, List.of(
                    formulaire("2026-11-19T09:00:00Z", Map.of("RUCVSPEC1", "Influvac Tetra", "RUCVNLOT1", "L12345")),
                    formulaire("2026-10-10T09:00:00Z", Map.of("RUCVSPEC1", "Revaxis"))));
        }

        private static ObservationMedicale observation(String texte, String quand) {
            return ObservationMedicale.builder().id(UUID.randomUUID()).patientId(JEANNE).chapitre("IDE")
                    .ecriteLe(Instant.parse(quand)).texte(texte).construire();
        }

        private static Formulaire formulaire(String creeLe, Map<String, String> valeurs) {
            return Formulaire.builder().id(UUID.randomUUID()).patientId(JEANNE).modele("CTX-RUCVCOMMENT1")
                    .libelleModele("Carnet de vaccination").creeLe(Instant.parse(creeLe)).valeurs(valeurs).construire();
        }
    }
}
