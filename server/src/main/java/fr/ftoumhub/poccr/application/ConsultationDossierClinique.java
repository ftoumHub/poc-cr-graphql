package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.dossier.Allergie;
import fr.ftoumhub.poccr.domaine.dossier.Antecedent;
import fr.ftoumhub.poccr.domaine.dossier.FiltreFormulaires;
import fr.ftoumhub.poccr.domaine.dossier.Formulaire;
import fr.ftoumhub.poccr.domaine.dossier.LignePrescription;
import fr.ftoumhub.poccr.domaine.dossier.NaturePrescription;
import fr.ftoumhub.poccr.domaine.dossier.ObservationMedicale;
import fr.ftoumhub.poccr.domaine.dossier.PersonneEntourage;
import fr.ftoumhub.poccr.domaine.dossier.RoleEntourage;
import fr.ftoumhub.poccr.domaine.dossier.Selection;
import fr.ftoumhub.poccr.domaine.dossier.StatutPrescription;
import fr.ftoumhub.poccr.domaine.dossier.TransmissionCiblee;
import fr.ftoumhub.poccr.domaine.port.DossierClinique;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cas d'utilisation : lire le dossier clinique (allergies, antécédents, prescriptions, observations,
 * transmissions, entourage, formulaires) pour alimenter un compte rendu. Lecture seule, chargements groupés.
 */
public class ConsultationDossierClinique {

    private final DossierClinique dossier;
    private final Clock horloge;

    public ConsultationDossierClinique(DossierClinique dossier, Clock horloge) {
        this.dossier = dossier;
        this.horloge = horloge;
    }

    public Map<UUID, List<Allergie>> allergiesDe(Set<UUID> patientIds, boolean activesSeulement) {
        Map<UUID, List<Allergie>> toutes = dossier.allergiesDe(patientIds);
        if (!activesSeulement) {
            return toutes;
        }
        LocalDate aujourdhui = LocalDate.now(horloge);
        return filtrer(toutes, a -> a.estActiveLe(aujourdhui));
    }

    public Map<UUID, List<Antecedent>> antecedentsDe(Set<UUID> patientIds) {
        return dossier.antecedentsDe(patientIds);
    }

    public Map<UUID, List<LignePrescription>> prescriptionsDe(Set<UUID> patientIds, NaturePrescription nature,
                                                              StatutPrescription statut) {
        return dossier.prescriptionsDe(patientIds, nature, statut);
    }

    public Map<UUID, List<ObservationMedicale>> observationsMedicalesDe(Set<UUID> patientIds, String chapitre,
                                                                        Integer derniers) {
        return limiter(dossier.observationsMedicalesDe(patientIds, chapitre), ObservationMedicale::ecriteLe, derniers);
    }

    public Map<UUID, List<TransmissionCiblee>> transmissionsCibleesDe(Set<UUID> patientIds, Integer derniers) {
        return limiter(dossier.transmissionsCibleesDe(patientIds), TransmissionCiblee::ecriteLe, derniers);
    }

    public Map<UUID, List<PersonneEntourage>> entourageDe(Set<UUID> patientIds, RoleEntourage role) {
        return filtrer(dossier.entourageDe(patientIds), p -> role == null || p.role() == role);
    }

    public Map<UUID, List<Formulaire>> formulairesDe(Set<UUID> patientIds, FiltreFormulaires filtre, Integer derniers) {
        return limiter(dossier.formulairesDe(patientIds, filtre), Formulaire::creeLe, derniers);
    }

    private static <T> Map<UUID, List<T>> filtrer(Map<UUID, List<T>> parPatient, java.util.function.Predicate<T> garder) {
        return parPatient.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream().filter(garder).toList()));
    }

    private static <T, D extends Comparable<? super D>> Map<UUID, List<T>> limiter(Map<UUID, List<T>> parPatient,
                                                                                  Function<T, D> date,
                                                                                  Integer derniers) {
        return parPatient.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> Selection.derniers(e.getValue(), date, derniers)));
    }
}
