package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.dossier.FiltreFormulaires;
import fr.ftoumhub.poccr.domaine.dossier.Formulaire;
import fr.ftoumhub.poccr.domaine.dossier.NaturePrescription;
import fr.ftoumhub.poccr.domaine.dossier.ObservationMedicale;
import fr.ftoumhub.poccr.domaine.dossier.RoleEntourage;
import fr.ftoumhub.poccr.domaine.dossier.StatutPrescription;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.TypeAdresse;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dossier clinique, identité étendue et séjour sur un PostgreSQL 16 jetable migré par Flyway : vérifie les
 * requêtes des adaptateurs et le jeu de données des gabarits convertis (migration V9). Ignoré sans Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class DossierCliniqueBaseReelleTest {

    @Container
    private static final PostgreSQLContainer<?> BASE = new PostgreSQLContainer<>("postgres:16");

    // patients de scénario de la migration V9
    private static final UUID DUBOIS = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID ROUSSEL = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private static DossierCliniqueJdbc dossier;
    private static PatientsJdbc patients;
    private static SejoursJdbc sejours;
    private static ProfessionnelsSanteJdbc professionnels;

    @BeforeAll
    static void migrer() {
        DriverManagerDataSource source =
                new DriverManagerDataSource(BASE.getJdbcUrl(), BASE.getUsername(), BASE.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        JdbcClient jdbc = JdbcClient.create(source);
        dossier = new DossierCliniqueJdbc(jdbc);
        patients = new PatientsJdbc(jdbc);
        sejours = new SejoursJdbc(jdbc);
        professionnels = new ProfessionnelsSanteJdbc(jdbc);
    }

    @Test
    void un_modele_de_contexte_se_retrouve_par_l_une_de_ses_rubriques_avec_toutes_ses_valeurs() {
        List<Formulaire> carnets = dossier.formulairesDe(Set.of(DUBOIS), new FiltreFormulaires(null, "RUCVSPEC1")).get(DUBOIS);

        assertThat(carnets).hasSize(2);
        assertThat(carnets.get(0).creeLe()).isAfter(carnets.get(1).creeLe());
        assertThat(carnets.get(0).libelleModele()).isEqualTo("Carnet de vaccination");
        assertThat(carnets.get(0).rubrique("RUCVSPEC1")).isPresent();
    }

    @Test
    void un_formulaire_designe_par_son_identifiant_d_origine_ne_rend_que_ce_modele() {
        List<Formulaire> ppi = dossier.formulairesDe(Set.of(DUBOIS, ROUSSEL),
                new FiltreFormulaires("9115405773860914995", null)).get(ROUSSEL);

        assertThat(ppi).extracting(Formulaire::modele).containsOnly("9115405773860914995");
        assertThat(ppi.get(0).valeurs()).isNotEmpty();
    }

    @Test
    void les_observations_d_un_chapitre_se_cherchent_sans_tenir_compte_de_la_casse() {
        List<ObservationMedicale> ide = dossier.observationsMedicalesDe(Set.of(DUBOIS), "ide").get(DUBOIS);

        assertThat(ide).isNotEmpty().allSatisfy(o -> assertThat(o.chapitre()).isEqualTo("IDE"));
        assertThat(ide).isSortedAccordingTo((a, b) -> b.ecriteLe().compareTo(a.ecriteLe()));
    }

    @Test
    void les_prescriptions_se_filtrent_par_nature_et_statut() {
        assertThat(dossier.prescriptionsDe(Set.of(DUBOIS), NaturePrescription.PLAN_SOINS, StatutPrescription.EN_COURS)
                .get(DUBOIS)).extracting(l -> l.libelle()).contains("Glycémie capillaire")
                .doesNotContain("Pansement talon droit");
    }

    @Test
    void le_patient_porte_son_identite_de_naissance_son_adresse_et_son_entourage() {
        Patient dubois = patients.parId(DUBOIS).orElseThrow();
        assertThat(dubois.nomDeNaissance()).isEqualTo("LEMAIRE");
        assertThat(dubois.ins()).isPresent();
        assertThat(dubois.medecinTraitantId()).isNotNull();

        List<Adresse> adresses = professionnels.adressesDe(Set.of(DUBOIS)).get(DUBOIS);
        assertThat(adresses).extracting(Adresse::type).containsExactly(TypeAdresse.PERSONNELLE);

        assertThat(dossier.entourageDe(Set.of(DUBOIS)).get(DUBOIS)).extracting(p -> p.role())
                .containsExactlyInAnyOrder(RoleEntourage.A_PREVENIR, RoleEntourage.CONFIANCE);
    }

    @Test
    void le_sejour_porte_ses_unites_fonctionnelles_et_son_medecin_adresseur() {
        Sejour ssr = sejours.dePatients(Set.of(DUBOIS)).get(DUBOIS).get(0);

        assertThat(ssr.ufMedicale().code()).isEqualTo("3000");
        assertThat(ssr.ufHebergement().telephone()).isNotBlank();
        assertThat(ssr.medecinAdresseurId()).isNotNull();
    }
}
