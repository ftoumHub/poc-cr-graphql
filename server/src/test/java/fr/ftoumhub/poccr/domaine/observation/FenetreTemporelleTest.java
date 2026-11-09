package fr.ftoumhub.poccr.domaine.observation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FenetreTemporelleTest {

    private static final LocalDate AUJOURDHUI = LocalDate.of(2026, 11, 7);

    @Test
    void une_duree_iso_est_relative_au_jour_de_redaction() {
        assertThat(FenetreTemporelle.debut("P3M", AUJOURDHUI)).contains(LocalDate.of(2026, 8, 7));
        assertThat(FenetreTemporelle.debut("P1Y", AUJOURDHUI)).contains(LocalDate.of(2025, 11, 7));
    }

    @Test
    void une_date_absolue_est_prise_telle_quelle() {
        assertThat(FenetreTemporelle.debut("2026-01-31", AUJOURDHUI)).contains(LocalDate.of(2026, 1, 31));
    }

    @Test
    void l_absence_de_valeur_signifie_pas_de_borne() {
        assertThat(FenetreTemporelle.debut(null, AUJOURDHUI)).isEmpty();
        assertThat(FenetreTemporelle.debut("  ", AUJOURDHUI)).isEmpty();
    }

    @Test
    void une_valeur_illisible_ou_negative_est_refusee() {
        assertThatThrownBy(() -> FenetreTemporelle.debut("3 mois", AUJOURDHUI))
                .isInstanceOf(FenetreTemporelle.FenetreTemporelleInvalide.class);
        assertThatThrownBy(() -> FenetreTemporelle.debut("P-3M", AUJOURDHUI))
                .isInstanceOf(FenetreTemporelle.FenetreTemporelleInvalide.class);
    }
}
