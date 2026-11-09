package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.domaine.observation.CategorieObservation;
import fr.ftoumhub.poccr.domaine.observation.FiltreObservations;
import fr.ftoumhub.poccr.domaine.observation.Observation;
import fr.ftoumhub.poccr.domaine.patient.Ipp;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.patient.Sexe;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.sejour.TypeSejour;
import fr.ftoumhub.poccr.domaine.port.Observations;
import fr.ftoumhub.poccr.domaine.port.Patients;
import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.port.Traitements;
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
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tranche GraphQL avec des ports en mémoire : vérifie le contrat utilisé par le plugin
 * (alias multiples, filtres) et le regroupement des appels au port observations.
 */
@GraphQlTest
@Import({ControleurDossierPatient.class, GestionErreursGraphQl.class, ControleurDossierPatientTest.PortsEnMemoire.class})
class ControleurDossierPatientTest {

    static final UUID CLAIRE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final LocalDate AUJOURDHUI = LocalDate.of(2026, 11, 7);

    @Autowired
    GraphQlTester graphQlTester;

    @Autowired
    JournalAppels journal;

    @Test
    void deux_liaisons_observations_aux_filtres_differents_tiennent_dans_une_requete() {
        journal.filtres.clear();

        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) {
                          c0: nom
                          c1: observations(categorie: BIOLOGIE, depuis: "P3M") { code valeur }
                          c2: observations(categorie: CONSTANTE) { code }
                        } }""")
                .variable("id", CLAIRE)
                .execute()
                .path("patient.c0").entity(String.class).isEqualTo("MARTIN")
                .path("patient.c1[*].code").entityList(String.class).containsExactly("HBA1C")
                .path("patient.c2[*].code").entityList(String.class).containsExactly("PAS");

        // un appel au port par filtre distinct, pas un par liaison ni par patient
        assertThat(journal.filtres).hasSize(2);
    }

    @Test
    void le_sejour_courant_et_la_liste_des_sejours_partagent_un_seul_chargement() {
        journal.chargementsSejours = 0;

        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) {
                          c0: sejourCourant { service dateEntree dureeJours type }
                          c1: sejours { numero }
                          c2: sejours(enCoursSeulement: true) { numero }
                        } }""")
                .variable("id", CLAIRE)
                .execute()
                .path("patient.c0.service").entity(String.class).isEqualTo("Endocrinologie")
                .path("patient.c0.dureeJours").entity(Integer.class).isEqualTo(3)
                .path("patient.c0.type").entity(String.class).isEqualTo("HOSPITALISATION_COMPLETE")
                .path("patient.c1[*].numero").entityList(String.class).hasSize(2)
                .path("patient.c2[*].numero").entityList(String.class).containsExactly("V2026000101");

        assertThat(journal.chargementsSejours).isEqualTo(1);
    }

    @Test
    void une_fenetre_temporelle_invalide_remonte_une_erreur_lisible() {
        graphQlTester.document("""
                        query($id: ID!) { patient(id: $id) { observations(depuis: "3 mois") { code } } }""")
                .variable("id", CLAIRE)
                .execute()
                .errors()
                .satisfy(erreurs -> assertThat(erreurs).anySatisfy(e ->
                        assertThat(e.getMessage()).contains("Fenêtre temporelle invalide")));
    }

    static class JournalAppels {
        final List<FiltreObservations> filtres = new ArrayList<>();
        int chargementsSejours;
    }

    /**
     * Configuration de démarrage dédiée : évite que la tranche reprenne PocCrApplication,
     * dont les @Import tirent la persistance JDBC.
     */
    @SpringBootConfiguration
    static class Contexte {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PortsEnMemoire {

        private final List<Observation> observations = List.of(
                observation(CategorieObservation.BIOLOGIE, "HBA1C", "7.2", AUJOURDHUI.minusDays(20)),
                observation(CategorieObservation.BIOLOGIE, "HBA1C", "7.9", AUJOURDHUI.minusDays(200)),
                observation(CategorieObservation.CONSTANTE, "PAS", "138", AUJOURDHUI.minusDays(2)));

        @Bean
        JournalAppels journalAppels() {
            return new JournalAppels();
        }

        @Bean
        ConsultationDossierPatient consultationDossierPatient(JournalAppels journal) {
            Patient claire = Patient.builder().id(CLAIRE).ipp(new Ipp("100001")).nom("MARTIN").prenom("Claire")
                    .dateNaissance(LocalDate.of(1968, 4, 12)).sexe(Sexe.FEMININ).construire();

            Patients patients = new Patients() {
                @Override
                public Optional<Patient> parId(UUID id) {
                    return CLAIRE.equals(id) ? Optional.of(claire) : Optional.empty();
                }

                @Override
                public List<Patient> rechercher(String texte, int limite) {
                    return List.of(claire);
                }

                @Override
                public fr.ftoumhub.poccr.domaine.ResultatPage<Patient> lister(
                        fr.ftoumhub.poccr.domaine.patient.FiltrePatients filtre, int page, int taille) {
                    return new fr.ftoumhub.poccr.domaine.ResultatPage<>(List.of(claire), 1);
                }
            };
            Observations ports = (ids, filtre) -> {
                journal.filtres.add(filtre);
                return observations.stream()
                        .filter(o -> ids.contains(o.patientId()))
                        .filter(o -> filtre.categorieAttendue().map(c -> c == o.categorie()).orElse(true))
                        .filter(o -> filtre.depuisLe().map(d -> !o.dateObservation().isBefore(d)).orElse(true))
                        .collect(Collectors.groupingBy(Observation::patientId));
            };
            Traitements traitements = ids -> Map.of();
            List<Sejour> sejoursClaire = List.of(
                    sejour("V2026000101", AUJOURDHUI.minusDays(3), null, "Endocrinologie"),
                    sejour("V2026000042", AUJOURDHUI.minusDays(125), AUJOURDHUI.minusDays(120), "Maladies infectieuses"));
            Sejours sejours = ids -> {
                journal.chargementsSejours++;
                return ids.contains(CLAIRE) ? Map.of(CLAIRE, sejoursClaire) : Map.of();
            };
            Clock horloge = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneId.of("Europe/Paris"));
            return new ConsultationDossierPatient(patients, ports, traitements, sejours, horloge);
        }

        private static Sejour sejour(String numero, LocalDate entree, LocalDate sortie, String service) {
            return Sejour.builder().id(UUID.randomUUID()).patientId(CLAIRE).numero(numero)
                    .type(TypeSejour.HOSPITALISATION_COMPLETE).dateEntree(entree).dateSortie(sortie)
                    .service(service).construire();
        }

        private static Observation observation(CategorieObservation categorie, String code, String valeur, LocalDate date) {
            return Observation.builder().id(UUID.randomUUID()).patientId(CLAIRE).categorie(categorie)
                    .code(code).libelle(code).valeur(valeur).dateObservation(date).construire();
        }
    }
}
