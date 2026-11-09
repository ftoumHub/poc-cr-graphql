package fr.ftoumhub.poccr.domaine.traitement;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraitementTest {

    private static final LocalDate DEBUT = LocalDate.of(2026, 3, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 3, 7);

    private static Traitement traitement(LocalDate fin) {
        return Traitement.builder().id(UUID.randomUUID()).patientId(UUID.randomUUID())
                .molecule("Amoxicilline 1 g").dateDebut(DEBUT).dateFin(fin).construire();
    }

    @Test
    void un_traitement_sans_date_de_fin_est_en_cours() {
        assertThat(traitement(null).estActifLe(LocalDate.of(2030, 1, 1))).isTrue();
    }

    @Test
    void les_bornes_de_debut_et_de_fin_sont_incluses() {
        assertThat(traitement(FIN).estActifLe(DEBUT)).isTrue();
        assertThat(traitement(FIN).estActifLe(FIN)).isTrue();
    }

    @Test
    void un_traitement_n_est_pas_actif_hors_de_sa_periode() {
        assertThat(traitement(FIN).estActifLe(DEBUT.minusDays(1))).isFalse();
        assertThat(traitement(FIN).estActifLe(FIN.plusDays(1))).isFalse();
    }
}
