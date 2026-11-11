package fr.ftoumhub.poccr.domaine.professionnel;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfessionnelSanteTest {

    private static ProfessionnelSante.Builder durand() {
        return ProfessionnelSante.builder().id(UUID.randomUUID()).code("DURAND").nom("DURAND").prenom("Sophie")
                .titre("Dr").type(TypeMedecin.ETABLISSEMENT);
    }

    @Test
    void l_identifiant_national_est_le_rpps_precede_de_8() {
        assertThat(durand().rpps("99000000011").construire().identifiantNational()).isEqualTo("899000000011");
    }

    @Test
    void sans_rpps_il_n_y_a_pas_d_identifiant_national() {
        assertThat(durand().construire().identifiantNational()).isNull();
    }

    @Test
    void un_rpps_compte_onze_chiffres() {
        assertThatThrownBy(() -> durand().rpps("1234").construire()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void le_libelle_reprend_titre_prenom_et_nom_presents() {
        assertThat(durand().construire().libelle()).isEqualTo("Dr Sophie DURAND");
        assertThat(durand().titre(null).prenom(" ").construire().libelle()).isEqualTo("DURAND");
    }
}
