package fr.ftoumhub.poccr.infrastructure.graphql;

import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Destinataire;
import fr.ftoumhub.poccr.domaine.document.DestinataireExterieur;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.Signataire;
import fr.ftoumhub.poccr.domaine.etablissement.Etablissement;
import fr.ftoumhub.poccr.domaine.professionnel.Adresse;
import fr.ftoumhub.poccr.domaine.professionnel.Coordonnees;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.professionnel.Telephone;
import fr.ftoumhub.poccr.domaine.professionnel.TypeAdresse;
import fr.ftoumhub.poccr.domaine.professionnel.TypeTelephone;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Adaptateur primaire GraphQL de la racine {@code document}.
 *
 * Quatre chargeurs groupés, partagés par tous les champs qui en ont besoin : acteurs (par document),
 * professionnels (par id), coordonnées (adresses + téléphones, par propriétaire) et établissements.
 * Le nombre de lectures dépend de la profondeur de la requête, jamais du nombre d'acteurs.
 * Aucun champ n'enchaîne deux chargeurs (un chargement déclenché après la répartition d'un niveau
 * ne serait pas groupé) : les coordonnées réunissent adresses et téléphones dans un même lot.
 */
@Controller
public class ControleurDocument {

    static final String CHARGEUR_ACTEURS = "acteursDocument";
    static final String CHARGEUR_PROFESSIONNELS = "professionnels";
    static final String CHARGEUR_COORDONNEES = "coordonnees";
    static final String CHARGEUR_ETABLISSEMENTS = "etablissements";

    private static final ZoneId FUSEAU = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter HEURE = DateTimeFormatter.ofPattern("HH:mm");

    /** Vues GraphQL sans équivalent direct dans le domaine. */
    record IdentifiantNationalVue(String identifiantNational, String rppsTranscode) {
    }

    record ImmatriculationVue(String adeli) {
    }

    record ValidationVue(Instant valideLe, UUID valideurId) {
    }

    private final ConsultationDocuments consultation;

    public ControleurDocument(ConsultationDocuments consultation, BatchLoaderRegistry registre) {
        this.consultation = consultation;
        registre.<UUID, ActeursDocument>forName(CHARGEUR_ACTEURS)
                .registerMappedBatchLoader((ids, env) -> Mono.fromSupplier(() -> consultation.acteursDe(ids)));
        registre.<UUID, ProfessionnelSante>forName(CHARGEUR_PROFESSIONNELS)
                .registerMappedBatchLoader((ids, env) -> Mono.fromSupplier(() -> consultation.professionnelsDe(ids)));
        registre.<UUID, Coordonnees>forName(CHARGEUR_COORDONNEES)
                .registerMappedBatchLoader((ids, env) -> Mono.fromSupplier(() -> consultation.coordonneesDe(ids)));
        registre.<UUID, Etablissement>forName(CHARGEUR_ETABLISSEMENTS)
                .registerMappedBatchLoader((ids, env) -> Mono.fromSupplier(() -> consultation.etablissementsDe(ids)));
    }

    @QueryMapping
    public Document document(@Argument UUID id) {
        return consultation.document(id).orElse(null);
    }

    // --- Document -------------------------------------------------------------------------------

    @SchemaMapping(typeName = "Document")
    public String dateCreation(Document document) {
        return date(document.creeLe());
    }

    @SchemaMapping(typeName = "Document")
    public String heureCreation(Document document) {
        return heure(document.creeLe());
    }

    @SchemaMapping(typeName = "Document")
    public String dateValeur(Document document) {
        return date(document.dateValeur());
    }

    @SchemaMapping(typeName = "Document")
    public String heureValeur(Document document) {
        return heure(document.dateValeur());
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<ProfessionnelSante> psCreateur(Document document, DataFetchingEnvironment env) {
        return document.auteurId() == null ? CompletableFuture.completedFuture(null)
                : professionnel(document.auteurId(), env);
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<Etablissement> etablissement(Document document, DataFetchingEnvironment env) {
        DataLoader<UUID, Etablissement> chargeur = env.getDataLoader(CHARGEUR_ETABLISSEMENTS);
        return chargeur.load(document.etablissementId());
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<Signataire> signatairePrincipal(Document document, DataFetchingEnvironment env) {
        return acteurs(document, env, a -> a.signatairePrincipal().orElse(null));
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<List<Signataire>> signatairesSecondaires(Document document, DataFetchingEnvironment env) {
        return acteurs(document, env, ActeursDocument::signatairesSecondaires);
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<List<Signataire>> signataires(Document document, DataFetchingEnvironment env) {
        return acteurs(document, env, ActeursDocument::signataires);
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<Destinataire> destinataire(Document document, DataFetchingEnvironment env) {
        return acteurs(document, env, a -> a.destinatairePrincipal().orElse(null));
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<List<Destinataire>> destinatairesEnCopie(Document document, DataFetchingEnvironment env) {
        return acteurs(document, env, ActeursDocument::copies);
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<List<DestinataireExterieur>> destinatairesExterieurs(Document document,
                                                                                 DataFetchingEnvironment env) {
        return acteurs(document, env, a -> a.exterieurs().stream().map(Destinataire::exterieur).toList());
    }

    @SchemaMapping(typeName = "Document")
    public CompletableFuture<Boolean> destinatairePatient(Document document, DataFetchingEnvironment env) {
        return acteurs(document, env, ActeursDocument::copieAuPatient);
    }

    @SchemaMapping(typeName = "Document")
    public ValidationVue validation(Document document) {
        return consultation.validation(document).map(v -> new ValidationVue(v.valideLe(), null)).orElse(null);
    }

    // --- Validation -----------------------------------------------------------------------------

    @SchemaMapping(typeName = "ValidationDocument")
    public String dateValidation(ValidationVue validation) {
        return date(validation.valideLe());
    }

    @SchemaMapping(typeName = "ValidationDocument")
    public String heureValidation(ValidationVue validation) {
        return heure(validation.valideLe());
    }

    @SchemaMapping(typeName = "ValidationDocument")
    public CompletableFuture<ProfessionnelSante> psValidant(ValidationVue validation, DataFetchingEnvironment env) {
        return validation.valideurId() == null ? CompletableFuture.completedFuture(null)
                : professionnel(validation.valideurId(), env);
    }

    // --- Signataire / Destinataire --------------------------------------------------------------

    @SchemaMapping(typeName = "Signataire")
    public CompletableFuture<ProfessionnelSante> medecin(Signataire signataire, DataFetchingEnvironment env) {
        return professionnel(signataire.professionnelId(), env);
    }

    /** « Transcodé » : sémantique à préciser (champ du contrat CDF non alimenté). */
    @SchemaMapping(typeName = "Signataire", field = "rppsTranscode")
    public String rppsTranscodeSignataire(Signataire signataire) {
        return null;
    }

    @SchemaMapping(typeName = "Destinataire", field = "medecin")
    public CompletableFuture<ProfessionnelSante> medecinDestinataire(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> civilite(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env)
                .thenApply(p -> p.civilite() == null ? null : p.civilite().name());
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> nom(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env).thenApply(ProfessionnelSante::nom);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> prenom(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env).thenApply(ProfessionnelSante::prenom);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> libelle(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env).thenApply(ProfessionnelSante::libelle);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> code(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env).thenApply(ProfessionnelSante::code);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> identifiantNational(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env).thenApply(ProfessionnelSante::identifiantNational);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> specialites(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env).thenApply(ProfessionnelSante::specialites);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> email(Destinataire d, DataFetchingEnvironment env) {
        return professionnel(d.professionnelId(), env).thenApply(ProfessionnelSante::email);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> telephone(Destinataire d, DataFetchingEnvironment env) {
        return numeroProfessionnel(d, TypeTelephone.FIXE, env);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> telephoneAutre(Destinataire d, DataFetchingEnvironment env) {
        return numeroProfessionnel(d, TypeTelephone.AUTRE, env);
    }

    @SchemaMapping(typeName = "Destinataire")
    public CompletableFuture<String> fax(Destinataire d, DataFetchingEnvironment env) {
        return numeroProfessionnel(d, TypeTelephone.FAX, env);
    }

    @SchemaMapping(typeName = "Destinataire", field = "adresse")
    public CompletableFuture<Adresse> adresseDestinataire(Destinataire d, DataFetchingEnvironment env) {
        return coordonnees(d.professionnelId(), env)
                .thenApply(c -> c.adresse(TypeAdresse.PROFESSIONNELLE).orElse(null));
    }

    private CompletableFuture<String> numeroProfessionnel(Destinataire d, TypeTelephone type,
                                                          DataFetchingEnvironment env) {
        return coordonnees(d.professionnelId(), env).thenApply(c -> c.telephone(TypeAdresse.PROFESSIONNELLE, type)
                .map(Telephone::numero).orElse(null));
    }

    // --- Medecin --------------------------------------------------------------------------------

    @SchemaMapping(typeName = "Medecin")
    public String typeMedecin(ProfessionnelSante p) {
        return p.type().name();
    }

    @SchemaMapping(typeName = "Medecin")
    public String specialitesConcatenees(ProfessionnelSante p) {
        return p.specialites();
    }

    /** « Transcodé » : sémantique à préciser (champ du contrat CDF non alimenté). */
    @SchemaMapping(typeName = "Medecin")
    public String rppsTranscode(ProfessionnelSante p) {
        return null;
    }

    @SchemaMapping(typeName = "Medecin")
    public IdentifiantNationalVue identifiantNationalActif(ProfessionnelSante p) {
        return p.identifiantNational() == null ? null : new IdentifiantNationalVue(p.identifiantNational(), null);
    }

    @SchemaMapping(typeName = "Medecin")
    public List<ImmatriculationVue> immatriculations(ProfessionnelSante p) {
        return p.adeli() == null ? List.of() : List.of(new ImmatriculationVue(p.adeli()));
    }

    @SchemaMapping(typeName = "Medecin", field = "adresse")
    public CompletableFuture<Adresse> adresseMedecin(ProfessionnelSante p, @Argument TypeAdresse type,
                                                     DataFetchingEnvironment env) {
        return coordonnees(p.id(), env).thenApply(c -> c.adresse(type).orElse(null));
    }

    // --- Etablissement / Adresse ----------------------------------------------------------------

    @SchemaMapping(typeName = "Etablissement", field = "adresse")
    public CompletableFuture<Adresse> adresseEtablissement(Etablissement e, DataFetchingEnvironment env) {
        return coordonnees(e.id(), env).thenApply(c -> c.adresse(TypeAdresse.PRINCIPALE).orElse(null));
    }

    @SchemaMapping(typeName = "Etablissement")
    public CompletableFuture<Telephone> telephonePrincipal(Etablissement e, DataFetchingEnvironment env) {
        return coordonnees(e.id(), env)
                .thenApply(c -> c.telephone(TypeAdresse.PRINCIPALE, TypeTelephone.PRINCIPAL).orElse(null));
    }

    @SchemaMapping(typeName = "Etablissement")
    public CompletableFuture<Telephone> telephoneSecondaire(Etablissement e, DataFetchingEnvironment env) {
        return coordonnees(e.id(), env)
                .thenApply(c -> c.telephone(TypeAdresse.PRINCIPALE, TypeTelephone.AUTRE).orElse(null));
    }

    /** Les coordonnées du propriétaire sont déjà en cache : l'adresse en vient. Aucune lecture supplémentaire. */
    @SchemaMapping(typeName = "Adresse", field = "telephone")
    public CompletableFuture<Telephone> telephoneAdresse(Adresse adresse, @Argument TypeTelephone type,
                                                         DataFetchingEnvironment env) {
        return coordonnees(adresse.proprietaireId(), env).thenApply(c -> c.telephone(adresse.id(), type).orElse(null));
    }

    // --- outils ---------------------------------------------------------------------------------

    private static <T> CompletableFuture<T> acteurs(Document document, DataFetchingEnvironment env,
                                                    Function<ActeursDocument, T> extraction) {
        DataLoader<UUID, ActeursDocument> chargeur = env.getDataLoader(CHARGEUR_ACTEURS);
        return chargeur.load(document.id())
                .thenApply(a -> extraction.apply(a == null ? ActeursDocument.AUCUN : a));
    }

    private static CompletableFuture<ProfessionnelSante> professionnel(UUID id, DataFetchingEnvironment env) {
        DataLoader<UUID, ProfessionnelSante> chargeur = env.getDataLoader(CHARGEUR_PROFESSIONNELS);
        return chargeur.load(id);
    }

    private static CompletableFuture<Coordonnees> coordonnees(UUID proprietaire, DataFetchingEnvironment env) {
        DataLoader<UUID, Coordonnees> chargeur = env.getDataLoader(CHARGEUR_COORDONNEES);
        return chargeur.load(proprietaire).thenApply(c -> c == null ? Coordonnees.AUCUNE : c);
    }

    private static String date(Instant instant) {
        return instant == null ? null : instant.atZone(FUSEAU).toLocalDate().toString();
    }

    private static String heure(Instant instant) {
        return instant == null ? null : HEURE.format(instant.atZone(FUSEAU));
    }
}
