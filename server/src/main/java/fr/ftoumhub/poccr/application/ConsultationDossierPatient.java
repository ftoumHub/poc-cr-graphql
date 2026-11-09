package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.ResultatPage;
import fr.ftoumhub.poccr.domaine.observation.CategorieObservation;
import fr.ftoumhub.poccr.domaine.observation.FenetreTemporelle;
import fr.ftoumhub.poccr.domaine.observation.FiltreObservations;
import fr.ftoumhub.poccr.domaine.observation.Observation;
import fr.ftoumhub.poccr.domaine.patient.FiltrePatients;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.port.Observations;
import fr.ftoumhub.poccr.domaine.port.Patients;
import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.port.Traitements;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.traitement.Traitement;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cas d'utilisation : consulter le dossier d'un patient pour alimenter un compte rendu.
 * Lecture seule. Le gel des valeurs (validation) sera un cas d'utilisation distinct.
 */
public class ConsultationDossierPatient {

    private static final int LIMITE_RECHERCHE = 20;

    private final Patients patients;
    private final Observations observations;
    private final Traitements traitements;
    private final Sejours sejours;
    private final Clock horloge;

    public ConsultationDossierPatient(Patients patients, Observations observations, Traitements traitements,
                                      Sejours sejours, Clock horloge) {
        this.patients = patients;
        this.observations = observations;
        this.traitements = traitements;
        this.sejours = sejours;
        this.horloge = horloge;
    }

    /** Liste paginée, filtrable colonne par colonne (page d'accueil, distincte de la recherche du plugin). */
    public ResultatPage<Patient> lister(FiltrePatients filtre, int page, int taille) {
        return patients.lister(filtre, page, taille);
    }

    public List<Patient> rechercher(String texte) {
        if (texte == null || texte.trim().length() < 2) {
            return List.of();
        }
        return patients.rechercher(texte.trim(), LIMITE_RECHERCHE);
    }

    public Optional<Patient> patient(UUID id) {
        return patients.parId(id);
    }

    public int ageDe(Patient patient) {
        return patient.ageLe(aujourdhui());
    }

    public FiltreObservations filtre(CategorieObservation categorie, String code, String depuis) {
        LocalDate debut = FenetreTemporelle.debut(depuis, aujourdhui()).orElse(null);
        return new FiltreObservations(categorie, code, debut);
    }

    public Map<UUID, List<Observation>> observationsDe(Set<UUID> patientIds, FiltreObservations filtre) {
        return observations.dePatients(patientIds, filtre);
    }

    public Map<UUID, List<Traitement>> traitementsDe(Set<UUID> patientIds, boolean actifsSeulement) {
        Map<UUID, List<Traitement>> parPatient = traitements.dePatients(patientIds);
        if (!actifsSeulement) {
            return parPatient;
        }
        LocalDate date = aujourdhui();
        return parPatient.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream().filter(t -> t.estActifLe(date)).toList()));
    }

    /** Tous les séjours des patients demandés (programmés compris), du plus récent au plus ancien. */
    public Map<UUID, List<Sejour>> sejoursDe(Set<UUID> patientIds) {
        return sejours.dePatients(patientIds);
    }

    public List<Sejour> filtrerSejours(List<Sejour> sejoursDuPatient, boolean enCoursSeulement) {
        if (!enCoursSeulement) {
            return sejoursDuPatient;
        }
        LocalDate date = aujourdhui();
        return sejoursDuPatient.stream().filter(s -> s.estEnCoursLe(date)).toList();
    }

    public Optional<Sejour> sejourDeReference(List<Sejour> sejoursDuPatient) {
        return Sejour.deReference(sejoursDuPatient, aujourdhui());
    }

    public long dureeEnJours(Sejour sejour) {
        return sejour.dureeEnJoursLe(aujourdhui());
    }

    private LocalDate aujourdhui() {
        return LocalDate.now(horloge);
    }
}
