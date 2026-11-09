package fr.ftoumhub.poccr.domaine.sejour;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SejourTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 11, 8);

    private static Sejour sejour(String numero, LocalDate entree, LocalDate sortie) {
        return Sejour.builder().id(UUID.randomUUID()).patientId(UUID.randomUUID()).numero(numero)
                .type(TypeSejour.HOSPITALISATION_COMPLETE).dateEntree(entree).dateSortie(sortie)
                .service("Endocrinologie").construire();
    }

    @Test
    void un_sejour_sans_sortie_est_en_cours_et_sa_duree_court_jusqu_a_aujourd_hui() {
        Sejour s = sejour("V1", AUJOURDHUI.minusDays(3), null);
        assertThat(s.estEnCoursLe(AUJOURDHUI)).isTrue();
        assertThat(s.dureeEnJoursLe(AUJOURDHUI)).isEqualTo(3);
    }

    @Test
    void une_venue_ambulatoire_dure_zero_jour() {
        assertThat(sejour("V1", AUJOURDHUI, AUJOURDHUI).dureeEnJoursLe(AUJOURDHUI)).isZero();
    }

    @Test
    void un_sejour_programme_n_est_ni_en_cours_ni_retenu_comme_reference() {
        Sejour programme = sejour("V9", AUJOURDHUI.plusDays(10), null);
        assertThat(programme.estEnCoursLe(AUJOURDHUI)).isFalse();
        assertThat(Sejour.deReference(List.of(programme), AUJOURDHUI)).isEmpty();
    }

    @Test
    void le_sejour_de_reference_est_le_sejour_en_cours_meme_s_il_existe_des_sejours_plus_anciens() {
        Sejour ancien = sejour("V1", AUJOURDHUI.minusDays(125), AUJOURDHUI.minusDays(120));
        Sejour enCours = sejour("V2", AUJOURDHUI.minusDays(3), null);
        Sejour programme = sejour("V3", AUJOURDHUI.plusDays(10), null);
        assertThat(Sejour.deReference(List.of(ancien, programme, enCours), AUJOURDHUI)).contains(enCours);
    }

    @Test
    void sans_sejour_en_cours_la_reference_est_le_dernier_sejour_termine() {
        Sejour ancien = sejour("V1", AUJOURDHUI.minusDays(125), AUJOURDHUI.minusDays(120));
        Sejour recent = sejour("V2", AUJOURDHUI.minusDays(30), AUJOURDHUI.minusDays(25));
        assertThat(Sejour.deReference(List.of(ancien, recent), AUJOURDHUI)).contains(recent);
        assertThat(Sejour.deReference(List.of(), AUJOURDHUI)).isEmpty();
    }

    @Test
    void une_sortie_anterieure_a_l_entree_est_refusee() {
        assertThatThrownBy(() -> sejour("V1", AUJOURDHUI, AUJOURDHUI.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
