package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.domaine.observation.CategorieObservation;
import fr.ftoumhub.poccr.domaine.observation.FiltreObservations;
import fr.ftoumhub.poccr.domaine.observation.Observation;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.traitement.Traitement;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Adaptateur primaire GraphQL.
 *
 * Les champs à arguments (observations, traitements) passent par des DataLoaders dont la clé
 * contient l'argument : {@code @BatchMapping} ne sait pas porter d'arguments.
 * Un document qui contient plusieurs liaisons « observations » avec des filtres différents
 * est envoyé par le plugin en une seule requête avec des alias ; chaque filtre distinct
 * donne une seule requête SQL, quel que soit le nombre de patients.
 */
@Controller
public class ControleurDossierPatient {

    static final String CHARGEUR_OBSERVATIONS = "observations";
    static final String CHARGEUR_TRAITEMENTS = "traitements";
    static final String CHARGEUR_SEJOURS = "sejours";

    private final ConsultationDossierPatient consultation;

    public ControleurDossierPatient(ConsultationDossierPatient consultation, BatchLoaderRegistry registre) {
        this.consultation = consultation;
        enregistrerChargeurs(registre);
    }

    @QueryMapping
    public List<Patient> rechercherPatients(@Argument String texte) {
        return consultation.rechercher(texte);
    }

    @QueryMapping
    public Patient patient(@Argument UUID id) {
        return consultation.patient(id).orElse(null);
    }

    /** Ipp est un value object du domaine : le schéma n'expose que sa valeur brute. */
    @SchemaMapping(typeName = "Patient")
    public String ipp(Patient patient) {
        return patient.ipp().valeur();
    }

    @SchemaMapping(typeName = "Patient")
    public int age(Patient patient) {
        return consultation.ageDe(patient);
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<Observation>> observations(Patient patient,
                                                             @Argument CategorieObservation categorie,
                                                             @Argument String code,
                                                             @Argument String depuis,
                                                             DataFetchingEnvironment env) {
        FiltreObservations filtre = consultation.filtre(categorie, code, depuis);
        DataLoader<CleObservations, List<Observation>> chargeur = env.getDataLoader(CHARGEUR_OBSERVATIONS);
        return chargeur.load(new CleObservations(patient.id(), filtre));
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<Traitement>> traitements(Patient patient,
                                                           @Argument Boolean actifsSeulement,
                                                           DataFetchingEnvironment env) {
        DataLoader<CleTraitements, List<Traitement>> chargeur = env.getDataLoader(CHARGEUR_TRAITEMENTS);
        return chargeur.load(new CleTraitements(patient.id(), Boolean.TRUE.equals(actifsSeulement)));
    }

    /**
     * sejours et sejourCourant partagent le même chargeur : un document qui demande les deux
     * ne déclenche qu'une requête SQL ; le filtrage et le choix du séjour de référence sont
     * des règles du domaine.
     */
    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<Sejour>> sejours(Patient patient, @Argument Boolean enCoursSeulement,
                                                   DataFetchingEnvironment env) {
        return chargerSejours(patient, env)
                .thenApply(s -> consultation.filtrerSejours(s, Boolean.TRUE.equals(enCoursSeulement)));
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<Sejour> sejourCourant(Patient patient, DataFetchingEnvironment env) {
        return chargerSejours(patient, env)
                .thenApply(s -> consultation.sejourDeReference(s).orElse(null));
    }

    @SchemaMapping(typeName = "Sejour")
    public int dureeJours(Sejour sejour) {
        return Math.toIntExact(consultation.dureeEnJours(sejour));
    }

    private CompletableFuture<List<Sejour>> chargerSejours(Patient patient, DataFetchingEnvironment env) {
        DataLoader<UUID, List<Sejour>> chargeur = env.getDataLoader(CHARGEUR_SEJOURS);
        return chargeur.load(patient.id());
    }

    private void enregistrerChargeurs(BatchLoaderRegistry registre) {
        registre.<CleObservations, List<Observation>>forName(CHARGEUR_OBSERVATIONS)
                .registerMappedBatchLoader((cles, env) -> Mono.fromSupplier(() -> chargerObservations(cles)));
        registre.<CleTraitements, List<Traitement>>forName(CHARGEUR_TRAITEMENTS)
                .registerMappedBatchLoader((cles, env) -> Mono.fromSupplier(() -> chargerTraitements(cles)));
        registre.<UUID, List<Sejour>>forName(CHARGEUR_SEJOURS)
                .registerMappedBatchLoader((ids, env) -> Mono.fromSupplier(() -> {
                    Map<UUID, List<Sejour>> parPatient = consultation.sejoursDe(ids);
                    Map<UUID, List<Sejour>> resultat = new HashMap<>();
                    ids.forEach(id -> resultat.put(id, parPatient.getOrDefault(id, List.of())));
                    return resultat;
                }));
    }

    private Map<CleObservations, List<Observation>> chargerObservations(Set<CleObservations> cles) {
        Map<CleObservations, List<Observation>> resultat = new HashMap<>();
        cles.stream()
                .collect(Collectors.groupingBy(CleObservations::filtre,
                        Collectors.mapping(CleObservations::patientId, Collectors.toSet())))
                .forEach((filtre, patientIds) -> {
                    Map<UUID, List<Observation>> parPatient = consultation.observationsDe(patientIds, filtre);
                    patientIds.forEach(id -> resultat.put(new CleObservations(id, filtre), parPatient.getOrDefault(id, List.of())));
                });
        return resultat;
    }

    private Map<CleTraitements, List<Traitement>> chargerTraitements(Set<CleTraitements> cles) {
        Map<CleTraitements, List<Traitement>> resultat = new HashMap<>();
        cles.stream()
                .collect(Collectors.groupingBy(CleTraitements::actifsSeulement,
                        Collectors.mapping(CleTraitements::patientId, Collectors.toSet())))
                .forEach((actifs, patientIds) -> {
                    Map<UUID, List<Traitement>> parPatient = consultation.traitementsDe(patientIds, actifs);
                    patientIds.forEach(id -> resultat.put(new CleTraitements(id, actifs), parPatient.getOrDefault(id, List.of())));
                });
        return resultat;
    }

    record CleObservations(UUID patientId, FiltreObservations filtre) {
    }

    record CleTraitements(UUID patientId, boolean actifsSeulement) {
    }
}
