package fr.ftoumhub.poccr.domaine.document;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActeursDocumentTest {

    private static final UUID DURAND = UUID.randomUUID();
    private static final UUID PETIT = UUID.randomUUID();
    private static final UUID GIRARD = UUID.randomUUID();
    private static final UUID ROUX = UUID.randomUUID();

    @Test
    void le_premier_signataire_saisi_est_le_signataire_principal() {
        ActeursDocument acteurs = ActeursDocument.saisis(List.of(DURAND, PETIT), null, List.of(), List.of(), false);

        assertThat(acteurs.signatairePrincipal()).get().extracting(Signataire::professionnelId).isEqualTo(DURAND);
        assertThat(acteurs.signatairesSecondaires()).extracting(Signataire::professionnelId).containsExactly(PETIT);
        assertThat(acteurs.signataires()).extracting(Signataire::rang).containsExactly(1, 2);
    }

    @Test
    void les_destinataires_gardent_l_ordre_de_saisie_dans_leur_role() {
        ActeursDocument acteurs = ActeursDocument.saisis(List.of(DURAND), GIRARD, List.of(ROUX, PETIT),
                List.of(new DestinataireExterieur("Mutuelle POC", null, null)), true);

        assertThat(acteurs.destinatairePrincipal()).get().extracting(Destinataire::professionnelId).isEqualTo(GIRARD);
        assertThat(acteurs.copies()).extracting(Destinataire::professionnelId).containsExactly(ROUX, PETIT);
        assertThat(acteurs.copies()).extracting(Destinataire::rang).containsExactly(1, 2);
        assertThat(acteurs.exterieurs()).extracting(d -> d.exterieur().nom()).containsExactly("Mutuelle POC");
        assertThat(acteurs.copieAuPatient()).isTrue();
        assertThat(acteurs.professionnels()).containsExactlyInAnyOrder(DURAND, GIRARD, ROUX, PETIT);
    }

    @Test
    void un_brouillon_peut_n_avoir_aucun_acteur() {
        ActeursDocument acteurs = ActeursDocument.saisis(List.of(), null, List.of(), List.of(), false);

        assertThat(acteurs).isEqualTo(ActeursDocument.AUCUN);
        assertThat(acteurs.signatairePrincipal()).isEmpty();
        assertThat(acteurs.destinatairePrincipal()).isEmpty();
        assertThat(acteurs.copieAuPatient()).isFalse();
    }

    @Test
    void un_professionnel_ne_signe_pas_deux_fois_le_meme_document() {
        assertThatThrownBy(() -> ActeursDocument.saisis(List.of(DURAND, DURAND), null, List.of(), List.of(), false))
                .isInstanceOf(ActeursInvalides.class)
                .hasMessageContaining("signe qu'une fois");
    }

    @Test
    void un_destinataire_principal_n_est_pas_aussi_en_copie() {
        assertThatThrownBy(() -> ActeursDocument.saisis(List.of(), GIRARD, List.of(GIRARD), List.of(), false))
                .isInstanceOf(ActeursInvalides.class)
                .hasMessageContaining("destinataire qu'une fois");
    }

    @Test
    void un_professionnel_n_est_pas_deux_fois_en_copie() {
        assertThatThrownBy(() -> ActeursDocument.saisis(List.of(), null, List.of(ROUX, ROUX), List.of(), false))
                .isInstanceOf(ActeursInvalides.class);
    }

    @Test
    void un_signataire_peut_aussi_etre_destinataire() {
        ActeursDocument acteurs = ActeursDocument.saisis(List.of(DURAND), DURAND, List.of(), List.of(), false);

        assertThat(acteurs.signatairePrincipal()).isPresent();
        assertThat(acteurs.destinatairePrincipal()).isPresent();
    }

    @Test
    void un_destinataire_exterieur_est_saisi_en_texte_libre_avec_un_nom() {
        assertThatThrownBy(() -> new DestinataireExterieur("  ", "Jean", null))
                .isInstanceOf(ActeursInvalides.class)
                .hasMessageContaining("nom");
        assertThatThrownBy(() -> new Destinataire(RoleDestinataire.EXTERIEUR, 1, GIRARD,
                new DestinataireExterieur("Mutuelle", null, null), null))
                .isInstanceOf(ActeursInvalides.class);
    }

    @Test
    void la_copie_au_patient_ne_designe_aucun_professionnel() {
        assertThatThrownBy(() -> Destinataire.professionnel(RoleDestinataire.PATIENT, 1, GIRARD))
                .isInstanceOf(ActeursInvalides.class);
        assertThatThrownBy(() -> Destinataire.professionnel(RoleDestinataire.COPIE, 1, null))
                .isInstanceOf(ActeursInvalides.class);
    }

    @Test
    void un_document_n_a_qu_un_destinataire_principal() {
        assertThatThrownBy(() -> ActeursDocument.reconstitues(List.of(), List.of(
                Destinataire.professionnel(RoleDestinataire.PRINCIPAL, 1, GIRARD),
                Destinataire.professionnel(RoleDestinataire.PRINCIPAL, 2, ROUX))))
                .isInstanceOf(ActeursInvalides.class)
                .hasMessageContaining("qu'un destinataire principal");
    }

    @Test
    void le_patient_ne_recoit_qu_une_copie() {
        assertThatThrownBy(() -> ActeursDocument.reconstitues(List.of(), List.of(
                Destinataire.patient(), new Destinataire(RoleDestinataire.PATIENT, 2, null, null, null))))
                .isInstanceOf(ActeursInvalides.class);
    }

    @Test
    void des_rangs_non_contigus_sont_refuses_a_la_reconstitution() {
        assertThatThrownBy(() -> ActeursDocument.reconstitues(
                List.of(new Signataire(DURAND, 1), new Signataire(PETIT, 3)), List.of()))
                .isInstanceOf(ActeursInvalides.class)
                .hasMessageContaining("se suivre");
        assertThatThrownBy(() -> ActeursDocument.reconstitues(
                List.of(new Signataire(PETIT, 2)), List.of()))
                .isInstanceOf(ActeursInvalides.class);
    }

    @Test
    void la_reconstitution_retrouve_la_saisie_quel_que_soit_l_ordre_de_lecture() {
        ActeursDocument saisis = ActeursDocument.saisis(List.of(DURAND, PETIT), GIRARD, List.of(ROUX),
                List.of(), true);
        List<Signataire> signataires = new java.util.ArrayList<>(saisis.signataires());
        List<Destinataire> destinataires = new java.util.ArrayList<>(saisis.destinataires());
        java.util.Collections.reverse(signataires);
        java.util.Collections.reverse(destinataires);

        assertThat(ActeursDocument.reconstitues(signataires, destinataires)).isEqualTo(saisis);
    }
}
