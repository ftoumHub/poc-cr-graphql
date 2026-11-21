package fr.ftoumhub.poccr.domaine.dossier;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectionTest {

    private static final LocalDate J = LocalDate.of(2026, 11, 21);
    private static final List<LocalDate> DATES = List.of(J.minusDays(10), J, J.minusDays(3));

    @Test
    void les_n_derniers_sont_les_plus_recents_du_plus_recent_au_plus_ancien() {
        assertThat(Selection.derniers(DATES, Function.identity(), 2)).containsExactly(J, J.minusDays(3));
    }

    @Test
    void sans_nombre_tous_les_elements_sont_gardes_tries() {
        assertThat(Selection.derniers(DATES, Function.identity(), null)).containsExactly(J, J.minusDays(3), J.minusDays(10));
    }

    @Test
    void un_nombre_nul_ou_negatif_est_refuse() {
        assertThatThrownBy(() -> Selection.derniers(DATES, Function.identity(), 0)).hasMessageContaining("positif");
    }
}
