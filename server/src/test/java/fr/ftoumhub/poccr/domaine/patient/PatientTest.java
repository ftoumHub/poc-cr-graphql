package fr.ftoumhub.poccr.domaine.patient;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatientTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 11, 21);

    private static Patient.Builder patient() {
        return Patient.builder().id(UUID.randomUUID()).ipp(new Ipp("100007")).nom("DUBOIS").prenom("Jeanne")
                .dateNaissance(LocalDate.of(1942, 8, 14));
    }

    @Test
    void l_age_en_texte_est_en_jours_avant_un_mois_en_mois_avant_deux_ans_puis_en_annees() {
        assertThat(patient().dateNaissance(AUJOURDHUI.minusDays(12)).construire().ageEnTexteLe(AUJOURDHUI)).isEqualTo("12 jours");
        assertThat(patient().dateNaissance(AUJOURDHUI.minusDays(1)).construire().ageEnTexteLe(AUJOURDHUI)).isEqualTo("1 jour");
        assertThat(patient().dateNaissance(AUJOURDHUI.minusMonths(8)).construire().ageEnTexteLe(AUJOURDHUI)).isEqualTo("8 mois");
        assertThat(patient().construire().ageEnTexteLe(AUJOURDHUI)).isEqualTo("84 ans");
    }

    @Test
    void le_titre_ne_suppose_jamais_un_genre_inconnu() {
        assertThat(patient().sexe(Sexe.FEMININ).construire().titre()).isEqualTo("Madame");
        assertThat(patient().sexe(Sexe.MASCULIN).construire().titre()).isEqualTo("Monsieur");
        assertThat(patient().sexe(Sexe.INCONNU).construire().titre()).isEqualTo("Madame, Monsieur");
        assertThat(patient().sexe(Sexe.INDETERMINE).construire().titre()).isEqualTo("Madame, Monsieur");
    }

    @Test
    void sans_nom_de_naissance_saisi_le_nom_de_naissance_est_le_nom_d_usage() {
        Patient sansNomDeNaissance = patient().construire();
        Patient mariee = patient().nomNaissance("LEMAIRE").construire();

        assertThat(sansNomDeNaissance.nomDeNaissance()).isEqualTo("DUBOIS");
        assertThat(sansNomDeNaissance.porteUnNomDUsage()).isFalse();
        assertThat(mariee.nomDeNaissance()).isEqualTo("LEMAIRE");
        assertThat(mariee.porteUnNomDUsage()).isTrue();
    }

    @Test
    void l_ins_n_existe_qu_avec_un_matricule_et_distingue_nir_et_nia() {
        assertThat(patient().construire().ins()).isEmpty();

        IdentiteNationaleSante nir = patient().nir("2420869123456").nomNaissance("LEMAIRE")
                .prenomsNaissance("Jeanne Marie Louise").lieuNaissance("69123").construire().ins().orElseThrow();
        assertThat(nir.natureMatricule()).isEqualTo("NIR");
        assertThat(nir.oid()).isEqualTo("1.2.250.1.213.1.4.8");
        assertThat(nir.nomNaissance()).isEqualTo("LEMAIRE");
        assertThat(nir.premierPrenom()).isEqualTo("Jeanne");
        assertThat(nir.prenomUsuel()).isEqualTo("Jeanne");

        IdentiteNationaleSante nia = patient().nir("7420869123456").construire().ins().orElseThrow();
        assertThat(nia.natureMatricule()).isEqualTo("NIA");
        assertThat(nia.oid()).isEqualTo("1.2.250.1.213.1.4.9");
    }
}
