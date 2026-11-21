package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.dossier.Allergie;
import fr.ftoumhub.poccr.domaine.dossier.Antecedent;
import fr.ftoumhub.poccr.domaine.dossier.FiltreFormulaires;
import fr.ftoumhub.poccr.domaine.dossier.Formulaire;
import fr.ftoumhub.poccr.domaine.dossier.LignePrescription;
import fr.ftoumhub.poccr.domaine.dossier.NaturePrescription;
import fr.ftoumhub.poccr.domaine.dossier.ObservationMedicale;
import fr.ftoumhub.poccr.domaine.dossier.PersonneEntourage;
import fr.ftoumhub.poccr.domaine.dossier.StatutPrescription;
import fr.ftoumhub.poccr.domaine.dossier.TransmissionCiblee;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Port secondaire : dossier clinique des patients (chargements groupés par patient).
 * Chaque méthode renvoie les éléments par patient ; un patient sans élément est absent de la table.
 */
public interface DossierClinique {

    Map<UUID, List<Allergie>> allergiesDe(Set<UUID> patientIds);

    Map<UUID, List<Antecedent>> antecedentsDe(Set<UUID> patientIds);

    /** Lignes de prescription ; nature ou statut null = sans filtre. */
    Map<UUID, List<LignePrescription>> prescriptionsDe(Set<UUID> patientIds, NaturePrescription nature,
                                                       StatutPrescription statut);

    /** Observations du chapitre (insensible à la casse) ; chapitre null = toutes. Plus récentes d'abord. */
    Map<UUID, List<ObservationMedicale>> observationsMedicalesDe(Set<UUID> patientIds, String chapitre);

    Map<UUID, List<TransmissionCiblee>> transmissionsCibleesDe(Set<UUID> patientIds);

    Map<UUID, List<PersonneEntourage>> entourageDe(Set<UUID> patientIds);

    /** Formulaires avec toutes leurs valeurs, plus récents d'abord. */
    Map<UUID, List<Formulaire>> formulairesDe(Set<UUID> patientIds, FiltreFormulaires filtre);
}
