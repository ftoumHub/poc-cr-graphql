package fr.ftoumhub.poccr.infrastructure.editeur;

import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.application.GestionDocuments;
import fr.ftoumhub.poccr.application.ValidationCompteRendu;
import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.DestinataireExterieur;
import fr.ftoumhub.poccr.domaine.document.DocumentFige;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.patient.Ipp;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.TypeMedecin;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrat REST des acteurs d'un document : forme du JSON et codes HTTP. */
@WebMvcTest
@Import(ControleurActeurs.class)
class ControleurActeursTest {

    private static final NomDocument CR = new NomDocument("cr-martin");
    private static final ProfessionnelSante PETIT = ps("PETIT", "Pr", "Anne");
    private static final ProfessionnelSante GIRARD = ps("GIRARD", "Dr", "Paul");
    private static final ProfessionnelSante ROUX = ps("ROUX", "Dr", "Isabelle");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GestionDocuments gestion;

    @MockitoBean
    ConsultationDocuments consultation;

    @MockitoBean
    ConsultationDossierPatient dossiers;

    @MockitoBean
    ValidationCompteRendu validation;

    @SpringBootConfiguration
    static class Contexte {
    }

    private static ProfessionnelSante ps(String nom, String titre, String prenom) {
        return ProfessionnelSante.builder().id(UUID.randomUUID()).code(nom).nom(nom).prenom(prenom).titre(titre)
                .specialites("Cardiologie").type(TypeMedecin.CORRESPONDANT).construire();
    }

    @Test
    void les_acteurs_sont_restitues_avec_les_libelles_et_l_etat_modifiable() throws Exception {
        ActeursDocument acteurs = ActeursDocument.saisis(List.of(PETIT.id()), GIRARD.id(), List.of(ROUX.id()),
                List.of(new DestinataireExterieur("Mutuelle POC", null, "contact@mutuelle.invalid")), true);
        when(gestion.acteurs(CR)).thenReturn(acteurs);
        when(consultation.professionnelsDe(acteurs.professionnels()))
                .thenReturn(Map.of(PETIT.id(), PETIT, GIRARD.id(), GIRARD, ROUX.id(), ROUX));
        when(validation.etat(CR)).thenReturn(Optional.empty());

        mvc.perform(get("/api/documents/cr-martin/acteurs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signataires[0].libelle").value("Pr Anne PETIT"))
                .andExpect(jsonPath("$.destinatairePrincipal.id").value(GIRARD.id().toString()))
                .andExpect(jsonPath("$.copies[0].code").value("ROUX"))
                .andExpect(jsonPath("$.exterieurs[0].nom").value("Mutuelle POC"))
                .andExpect(jsonPath("$.copieAuPatient").value(true))
                .andExpect(jsonPath("$.modifiable").value(true));
    }

    @Test
    void la_saisie_remplace_les_acteurs_dans_l_ordre_donne() throws Exception {
        mvc.perform(put("/api/documents/cr-martin/acteurs").contentType(MediaType.APPLICATION_JSON).content("""
                        {"signataires":["%s","%s"],"destinatairePrincipal":"%s","copies":[],
                         "exterieurs":[{"nom":"Mutuelle POC"}],"copieAuPatient":true}"""
                        .formatted(PETIT.id(), ROUX.id(), GIRARD.id())))
                .andExpect(status().isNoContent());

        ArgumentCaptor<ActeursDocument> saisis = ArgumentCaptor.forClass(ActeursDocument.class);
        verify(gestion).definirActeurs(eq(CR), saisis.capture());
        assertThat(saisis.getValue().signatairePrincipal()).get().extracting(s -> s.professionnelId())
                .isEqualTo(PETIT.id());
        assertThat(saisis.getValue().signatairesSecondaires()).hasSize(1);
        assertThat(saisis.getValue().copieAuPatient()).isTrue();
    }

    @Test
    void une_saisie_minimale_vide_les_acteurs() throws Exception {
        mvc.perform(put("/api/documents/cr-martin/acteurs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());

        verify(gestion).definirActeurs(CR, ActeursDocument.AUCUN);
    }

    @Test
    void une_regle_violee_renvoie_400_avec_le_message_metier() throws Exception {
        mvc.perform(put("/api/documents/cr-martin/acteurs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signataires\":[\"%s\",\"%s\"]}".formatted(PETIT.id(), PETIT.id())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Un professionnel ne signe qu'une fois le même document"));

        verify(gestion, never()).definirActeurs(any(), any());
    }

    @Test
    void les_acteurs_d_un_compte_rendu_valide_renvoient_409() throws Exception {
        doThrow(new DocumentFige(CR, "les signataires et destinataires")).when(gestion).definirActeurs(any(), any());

        mvc.perform(put("/api/documents/cr-martin/acteurs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Le compte rendu cr-martin est validé : impossible de modifier les signataires et destinataires"));
    }

    @Test
    void un_document_inconnu_renvoie_404() throws Exception {
        when(gestion.acteurs(CR)).thenThrow(new DocumentIntrouvable(CR));

        mvc.perform(get("/api/documents/cr-martin/acteurs")).andExpect(status().isNotFound());
    }

    @Test
    void rattacher_un_patient_inconnu_renvoie_400() throws Exception {
        UUID inconnu = UUID.randomUUID();
        when(dossiers.patient(inconnu)).thenReturn(Optional.empty());

        mvc.perform(put("/api/documents/cr-martin/patient").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\"}".formatted(inconnu)))
                .andExpect(status().isBadRequest());
        verify(gestion, never()).rattacherPatient(any(), any());
    }

    @Test
    void rattacher_un_patient_connu_le_transmet() throws Exception {
        UUID martin = UUID.randomUUID();
        when(dossiers.patient(martin)).thenReturn(Optional.of(Patient.builder().id(martin).ipp(new Ipp("100001"))
                .nom("MARTIN").dateNaissance(LocalDate.of(1968, 4, 12)).construire()));

        mvc.perform(put("/api/documents/cr-martin/patient").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patientId\":\"%s\"}".formatted(martin)))
                .andExpect(status().isNoContent());
        verify(gestion).rattacherPatient(CR, martin);
    }

    @Test
    void la_recherche_de_professionnels_renvoie_des_resumes() throws Exception {
        when(consultation.rechercherProfessionnels("gi")).thenReturn(List.of(GIRARD));

        mvc.perform(get("/api/professionnels").param("texte", "gi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].libelle").value("Dr Paul GIRARD"))
                .andExpect(jsonPath("$[0].typeMedecin").value("CORRESPONDANT"));
    }

}
