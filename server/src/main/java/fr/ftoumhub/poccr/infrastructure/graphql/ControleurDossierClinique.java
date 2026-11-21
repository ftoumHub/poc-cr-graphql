package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.application.ConsultationDossierClinique;
import fr.ftoumhub.poccr.domaine.dossier.Allergie;
import fr.ftoumhub.poccr.domaine.dossier.Antecedent;
import fr.ftoumhub.poccr.domaine.dossier.FiltreFormulaires;
import fr.ftoumhub.poccr.domaine.dossier.Formulaire;
import fr.ftoumhub.poccr.domaine.dossier.LignePrescription;
import fr.ftoumhub.poccr.domaine.dossier.NaturePrescription;
import fr.ftoumhub.poccr.domaine.dossier.ObservationMedicale;
import fr.ftoumhub.poccr.domaine.dossier.PersonneEntourage;
import fr.ftoumhub.poccr.domaine.dossier.RoleEntourage;
import fr.ftoumhub.poccr.domaine.dossier.StatutPrescription;
import fr.ftoumhub.poccr.domaine.dossier.TransmissionCiblee;
import fr.ftoumhub.poccr.domaine.patient.IdentiteNationaleSante;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.Coordonnees;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.TypeAdresse;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Adaptateur primaire GraphQL du dossier clinique (champs du patient ajoutés pour les gabarits legacy).
 *
 * Chaque liste passe par un DataLoader dont la clé porte le patient et le filtre : deux liaisons aux
 * filtres différents font deux lectures, quel que soit le nombre de patients ; les mêmes filtres n'en
 * font qu'une. Les adresses et les professionnels (médecin traitant, auteur d'une observation, médecin
 * adresseur) réutilisent les chargeurs « coordonnees » et « professionnels » de {@link ControleurDocument}.
 */
@Controller
public class ControleurDossierClinique {

    static final String CHARGEUR_ALLERGIES = "allergies";
    static final String CHARGEUR_ANTECEDENTS = "antecedents";
    static final String CHARGEUR_PRESCRIPTIONS = "prescriptions";
    static final String CHARGEUR_OBSERVATIONS_MEDICALES = "observationsMedicales";
    static final String CHARGEUR_TRANSMISSIONS = "transmissionsCiblees";
    static final String CHARGEUR_ENTOURAGE = "entourage";
    static final String CHARGEUR_FORMULAIRES = "formulaires";

    private static final ZoneId FUSEAU = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

    /** Clé de chargement : un patient et le filtre demandé (record : égalité structurelle). */
    record Cle<F>(UUID patientId, F filtre) {
    }

    record FiltrePrescriptions(NaturePrescription nature, StatutPrescription statut) {
    }

    record FiltreObservationsMedicales(String chapitre, Integer derniers) {
    }

    record FiltreFormulairesLimite(FiltreFormulaires filtre, Integer derniers) {
    }

    private final ConsultationDossierClinique consultation;

    public ControleurDossierClinique(ConsultationDossierClinique consultation, BatchLoaderRegistry registre) {
        this.consultation = consultation;
        enregistrer(registre, CHARGEUR_ALLERGIES,
                (Set<UUID> ids, Boolean actives) -> consultation.allergiesDe(ids, actives));
        enregistrer(registre, CHARGEUR_ANTECEDENTS, (Set<UUID> ids, Boolean sans) -> consultation.antecedentsDe(ids));
        enregistrer(registre, CHARGEUR_PRESCRIPTIONS,
                (Set<UUID> ids, FiltrePrescriptions f) -> consultation.prescriptionsDe(ids, f.nature(), f.statut()));
        enregistrer(registre, CHARGEUR_OBSERVATIONS_MEDICALES, (Set<UUID> ids, FiltreObservationsMedicales f) ->
                consultation.observationsMedicalesDe(ids, f.chapitre(), f.derniers()));
        enregistrer(registre, CHARGEUR_TRANSMISSIONS,
                (Set<UUID> ids, Integer derniers) -> consultation.transmissionsCibleesDe(ids, derniers));
        enregistrer(registre, CHARGEUR_ENTOURAGE,
                (Set<UUID> ids, Boolean sans) -> consultation.entourageDe(ids, null));
        enregistrer(registre, CHARGEUR_FORMULAIRES, (Set<UUID> ids, FiltreFormulairesLimite f) ->
                consultation.formulairesDe(ids, f.filtre(), f.derniers()));
    }

    // --- Patient : identité et entourage ----------------------------------------------------------

    @SchemaMapping(typeName = "Patient", field = "adresse")
    public CompletableFuture<Adresse> adressePatient(Patient patient, @Argument TypeAdresse type,
                                                     DataFetchingEnvironment env) {
        return coordonnees(patient.id(), env).thenApply(c -> c.adresse(type).orElse(null));
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<ProfessionnelSante> medecinTraitant(Patient patient, DataFetchingEnvironment env) {
        return professionnel(patient.medecinTraitantId(), env);
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<PersonneEntourage> personneAPrevenir(Patient patient, DataFetchingEnvironment env) {
        return personne(patient, RoleEntourage.A_PREVENIR, env);
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<PersonneEntourage> personneDeConfiance(Patient patient, DataFetchingEnvironment env) {
        return personne(patient, RoleEntourage.CONFIANCE, env);
    }

    @SchemaMapping(typeName = "PersonneEntourage")
    public String lienParente(PersonneEntourage personne) {
        return personne.lien();
    }

    @SchemaMapping(typeName = "PersonneEntourage", field = "adresse")
    public CompletableFuture<Adresse> adressePersonne(PersonneEntourage personne, @Argument TypeAdresse type,
                                                      DataFetchingEnvironment env) {
        return coordonnees(personne.id(), env).thenApply(c -> c.adresse(type).orElse(null));
    }

    @SchemaMapping(typeName = "IdentiteNationaleSante")
    public String nom(IdentiteNationaleSante ins) {
        return ins.nomNaissance();
    }

    @SchemaMapping(typeName = "IdentiteNationaleSante")
    public String prenom(IdentiteNationaleSante ins) {
        return ins.premierPrenom();
    }

    @SchemaMapping(typeName = "IdentiteNationaleSante")
    public String autresPrenoms(IdentiteNationaleSante ins) {
        return ins.prenomsNaissance();
    }

    // --- Patient : dossier clinique ---------------------------------------------------------------

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<Allergie>> allergies(Patient patient, @Argument Boolean activesSeulement,
                                                       DataFetchingEnvironment env) {
        return charger(env, CHARGEUR_ALLERGIES, patient, !Boolean.FALSE.equals(activesSeulement));
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<Antecedent>> antecedents(Patient patient, DataFetchingEnvironment env) {
        return charger(env, CHARGEUR_ANTECEDENTS, patient, Boolean.TRUE);
    }

    @SchemaMapping(typeName = "Antecedent")
    public String libelleCategorie(Antecedent antecedent) {
        return antecedent.categorie();
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<LignePrescription>> prescriptions(Patient patient,
                                                                    @Argument NaturePrescription nature,
                                                                    @Argument StatutPrescription statut,
                                                                    DataFetchingEnvironment env) {
        return charger(env, CHARGEUR_PRESCRIPTIONS, patient, new FiltrePrescriptions(nature, statut));
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<ObservationMedicale>> observationsMedicales(Patient patient,
                                                                              @Argument String chapitre,
                                                                              @Argument Integer derniers,
                                                                              DataFetchingEnvironment env) {
        return charger(env, CHARGEUR_OBSERVATIONS_MEDICALES, patient,
                new FiltreObservationsMedicales(chapitre, derniers));
    }

    @SchemaMapping(typeName = "ObservationMedicale")
    public String date(ObservationMedicale observation) {
        return date(observation.ecriteLe());
    }

    @SchemaMapping(typeName = "ObservationMedicale")
    public String heure(ObservationMedicale observation) {
        return heure(observation.ecriteLe());
    }

    @SchemaMapping(typeName = "ObservationMedicale")
    public CompletableFuture<ProfessionnelSante> auteur(ObservationMedicale observation, DataFetchingEnvironment env) {
        return professionnel(observation.auteurId(), env);
    }

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<TransmissionCiblee>> transmissionsCiblees(Patient patient,
                                                                            @Argument Integer derniers,
                                                                            DataFetchingEnvironment env) {
        return charger(env, CHARGEUR_TRANSMISSIONS, patient, derniers);
    }

    @SchemaMapping(typeName = "TransmissionCiblee")
    public String dateHeure(TransmissionCiblee transmission) {
        return date(transmission.ecriteLe()) + " " + heure(transmission.ecriteLe());
    }

    // --- Formulaires ------------------------------------------------------------------------------

    @SchemaMapping(typeName = "Patient")
    public CompletableFuture<List<Formulaire>> formulaires(Patient patient, @Argument String modele,
                                                           @Argument String contenant, @Argument Integer derniers,
                                                           DataFetchingEnvironment env) {
        return charger(env, CHARGEUR_FORMULAIRES, patient,
                new FiltreFormulairesLimite(new FiltreFormulaires(modele, contenant), derniers));
    }

    @SchemaMapping(typeName = "Formulaire")
    public String libelle(Formulaire formulaire) {
        return formulaire.libelleModele();
    }

    @SchemaMapping(typeName = "Formulaire")
    public String dateCreation(Formulaire formulaire) {
        return date(formulaire.creeLe());
    }

    @SchemaMapping(typeName = "Formulaire")
    public String dateModification(Formulaire formulaire) {
        return date(formulaire.modifieLe());
    }

    /** Les valeurs sont chargées avec le formulaire : aucune lecture par rubrique. */
    @SchemaMapping(typeName = "Formulaire")
    public String rubrique(Formulaire formulaire, @Argument String code) {
        return formulaire.rubrique(code).orElse(null);
    }

    // --- Séjour -----------------------------------------------------------------------------------

    @SchemaMapping(typeName = "Sejour")
    public UniteFonctionnelle ufMedicaleCourante(Sejour sejour) {
        return sejour.ufMedicale();
    }

    @SchemaMapping(typeName = "Sejour")
    public UniteFonctionnelle ufHebergementCourante(Sejour sejour) {
        return sejour.ufHebergement();
    }

    @SchemaMapping(typeName = "Sejour")
    public CompletableFuture<ProfessionnelSante> medecinAdresseur(Sejour sejour, DataFetchingEnvironment env) {
        return professionnel(sejour.medecinAdresseurId(), env);
    }

    // --- outils -----------------------------------------------------------------------------------

    private static <F, V> void enregistrer(BatchLoaderRegistry registre, String nom,
                                           BiFunction<Set<UUID>, F, Map<UUID, List<V>>> lecture) {
        registre.<Cle<F>, List<V>>forName(nom)
                .registerMappedBatchLoader((cles, env) -> Mono.fromSupplier(() -> parFiltre(cles, lecture)));
    }

    /** Une lecture par filtre distinct (null compris : « sans filtre »), pour tous les patients qui le demandent. */
    static <F, V> Map<Cle<F>, List<V>> parFiltre(Set<Cle<F>> cles, BiFunction<Set<UUID>, F, Map<UUID, List<V>>> lecture) {
        Map<F, Set<UUID>> patientsParFiltre = new HashMap<>();
        cles.forEach(c -> patientsParFiltre.computeIfAbsent(c.filtre(), f -> new HashSet<>()).add(c.patientId()));
        Map<Cle<F>, List<V>> resultat = new HashMap<>();
        patientsParFiltre.forEach((filtre, patientIds) -> {
            Map<UUID, List<V>> parPatient = lecture.apply(patientIds, filtre);
            patientIds.forEach(id -> resultat.put(new Cle<>(id, filtre), parPatient.getOrDefault(id, List.of())));
        });
        return resultat;
    }

    private static <F, V> CompletableFuture<List<V>> charger(DataFetchingEnvironment env, String chargeur,
                                                             Patient patient, F filtre) {
        DataLoader<Cle<F>, List<V>> loader = env.getDataLoader(chargeur);
        return loader.load(new Cle<>(patient.id(), filtre));
    }

    private static CompletableFuture<PersonneEntourage> personne(Patient patient, RoleEntourage role,
                                                                 DataFetchingEnvironment env) {
        CompletableFuture<List<PersonneEntourage>> entourage = charger(env, CHARGEUR_ENTOURAGE, patient, Boolean.TRUE);
        return entourage.thenApply(l -> l.stream().filter(p -> p.role() == role).findFirst().orElse(null));
    }

    private static CompletableFuture<ProfessionnelSante> professionnel(UUID id, DataFetchingEnvironment env) {
        if (id == null) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<UUID, ProfessionnelSante> chargeur = env.getDataLoader(ControleurDocument.CHARGEUR_PROFESSIONNELS);
        return chargeur.load(id);
    }

    private static CompletableFuture<Coordonnees> coordonnees(UUID proprietaire, DataFetchingEnvironment env) {
        DataLoader<UUID, Coordonnees> chargeur = env.getDataLoader(ControleurDocument.CHARGEUR_COORDONNEES);
        return chargeur.load(proprietaire).thenApply(c -> c == null ? Coordonnees.AUCUNE : c);
    }

    private static String date(Instant instant) {
        return instant == null ? null : instant.atZone(FUSEAU).toLocalDate().toString();
    }

    private static String heure(Instant instant) {
        return instant == null ? null : HEURE.format(instant.atZone(FUSEAU));
    }
}
