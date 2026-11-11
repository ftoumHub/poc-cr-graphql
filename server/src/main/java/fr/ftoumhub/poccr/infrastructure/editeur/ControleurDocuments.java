package fr.ftoumhub.poccr.infrastructure.editeur;

import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.application.GestionDocuments;
import fr.ftoumhub.poccr.application.ValidationCompteRendu;
import fr.ftoumhub.poccr.application.ValidationCompteRendu.CompteRenduDejaValide;
import fr.ftoumhub.poccr.application.ValidationCompteRendu.ValidationImpossible;
import fr.ftoumhub.poccr.domaine.ResultatPage;
import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.document.Signataire;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.patient.FiltrePatients;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier.RequeteRejetee;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hébergement minimal pour OnlyOffice Document Server + validation des comptes rendus.
 * JWT désactivé côté Document Server pour le POC : à réactiver avant toute démo hors poste.
 */
@RestController
@RequestMapping("/api")
public class ControleurDocuments {

    private static final Logger LOG = LoggerFactory.getLogger(ControleurDocuments.class);
    private static final MediaType DOCX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    /** Statuts de callback OnlyOffice porteurs d'un document à enregistrer. */
    private static final int PRET_A_ENREGISTRER = 2;
    private static final int ENREGISTREMENT_FORCE = 6;

    /** En attendant l'authentification : nom de l'utilisateur affiché par l'éditeur (le valideur est le signataire principal). */
    private static final String UTILISATEUR_EDITEUR = "Dr POC";

    private final StockageDocuments stockage;
    private final GestionDocuments gestion;
    private final ValidationCompteRendu validation;
    private final ConsultationDossierPatient consultation;
    private final ConsultationDocuments consultationDocuments;
    private final ProprietesEditeur proprietes;
    private final HttpClient http = HttpClient.newHttpClient();

    public ControleurDocuments(StockageDocuments stockage, GestionDocuments gestion, ValidationCompteRendu validation,
                               ConsultationDossierPatient consultation, ConsultationDocuments consultationDocuments,
                               ProprietesEditeur proprietes) {
        this.stockage = stockage;
        this.gestion = gestion;
        this.validation = validation;
        this.consultation = consultation;
        this.consultationDocuments = consultationDocuments;
        this.proprietes = proprietes;
    }

    @GetMapping("/editeur/parametres")
    public Map<String, String> parametres() {
        return Map.of("urlDocumentServer", proprietes.urlDocumentServer());
    }

    /** Vue REST d'un patient : les value objects du domaine (Ipp) s'y résolvent en valeur brute. */
    public record PatientResume(UUID id, String ipp, String nom, String prenom, String dateNaissance, String sexe) {
        static PatientResume de(Patient p) {
            return new PatientResume(p.id(), p.ipp().valeur(), p.nom(), p.prenom(),
                    p.dateNaissance().toString(), p.sexe().name());
        }
    }

    /** Liste paginée et filtrable des patients, pour le tableau de la page d'accueil. */
    @GetMapping("/patients")
    public ResultatPage<PatientResume> listerPatients(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "10") int taille,
                                                       @RequestParam(required = false) String ipp,
                                                       @RequestParam(required = false) String nom,
                                                       @RequestParam(required = false) String prenom,
                                                       @RequestParam(required = false) String sexe,
                                                       @RequestParam(required = false) String dateNaissance) {
        ResultatPage<Patient> resultat = consultation.lister(new FiltrePatients(ipp, nom, prenom, sexe, dateNaissance), page, taille);
        return new ResultatPage<>(resultat.elements().stream().map(PatientResume::de).toList(), resultat.total());
    }

    /** Un repère par document : identifiant, patient (si connu), signataire principal et statut de validation. */
    public record ResumeDocument(UUID id, String nom, String statut, String valideLe,
                                 String patientNom, String patientPrenom, String patientIpp, UUID patientId,
                                 String signatairePrincipal) {
    }

    @GetMapping("/documents")
    public List<ResumeDocument> lister() {
        Map<String, Document> fiches = gestion.tous().stream()
                .collect(Collectors.toMap(d -> d.nom().valeur(), d -> d));
        Map<UUID, ActeursDocument> acteurs = consultationDocuments.acteursDe(
                fiches.values().stream().map(Document::id).collect(Collectors.toSet()));
        Set<UUID> principaux = acteurs.values().stream()
                .flatMap(a -> a.signatairePrincipal().stream()).map(Signataire::professionnelId)
                .collect(Collectors.toSet());
        Map<UUID, ProfessionnelSante> ps = consultationDocuments.professionnelsDe(principaux);
        return stockage.lister().stream().map(nom -> {
            Document fiche = fiches.get(nom);
            String signataire = fiche == null ? null : acteurs.getOrDefault(fiche.id(), ActeursDocument.AUCUN)
                    .signatairePrincipal().map(s -> ps.get(s.professionnelId()))
                    .map(ProfessionnelSante::libelle).orElse(null);
            return resume(nom, fiche, signataire);
        }).toList();
    }

    private ResumeDocument resume(String nom, Document fiche, String signataire) {
        UUID id = fiche == null ? null : fiche.id();
        Optional<Validation> v = validation.etat(new NomDocument(nom));
        if (v.isPresent()) {
            Validation val = v.get();
            Optional<Patient> patient = consultation.patient(val.patientId());
            return new ResumeDocument(id, nom, val.estScelle() ? "scelle" : "valide", val.valideLe().toString(),
                    patient.map(Patient::nom).orElse(null),
                    patient.map(Patient::prenom).orElse(null),
                    patient.map(p -> p.ipp().valeur()).orElse(null),
                    val.patientId(), signataire);
        }
        // Brouillon : patient de la fiche ; à défaut (plugin antérieur au rattachement), celui des
        // tags des content controls (lecture au mieux, voir StockageDocuments).
        Optional<UUID> patientId = fiche != null && fiche.patientId() != null
                ? Optional.of(fiche.patientId()) : stockage.patientLie(nom);
        Optional<Patient> patient = patientId.flatMap(consultation::patient);
        return new ResumeDocument(id, nom, "brouillon", null,
                patient.map(Patient::nom).orElse(null),
                patient.map(Patient::prenom).orElse(null),
                patient.map(p -> p.ipp().valeur()).orElse(null),
                patientId.orElse(null), signataire);
    }

    @PostMapping("/documents")
    public ResponseEntity<Void> creer(@RequestParam String nom) {
        gestion.creer(new NomDocument(nom), null);
        return ResponseEntity.created(URI.create("/api/documents/" + nom)).build();
    }

    /**
     * Duplique un document (validé ou non) sous un nouveau nom : la copie démarre comme un
     * brouillon éditable qui réutilise les content controls de la source, sans sa validation.
     */
    @PostMapping("/documents/{nom}/dupliquer")
    public ResponseEntity<Void> dupliquer(@PathVariable String nom, @RequestParam String nouveauNom) {
        gestion.dupliquer(new NomDocument(nom), new NomDocument(nouveauNom));
        return ResponseEntity.created(URI.create("/api/documents/" + nouveauNom)).build();
    }

    /** Un compte rendu validé ne peut pas être renommé : son nom fait partie des données figées (409). */
    @PutMapping("/documents/{nom}")
    public ResponseEntity<?> renommer(@PathVariable String nom, @RequestParam String nouveauNom) {
        gestion.renommer(new NomDocument(nom), new NomDocument(nouveauNom));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents/{nom}/contenu")
    public ResponseEntity<byte[]> contenu(@PathVariable String nom) {
        return ResponseEntity.ok().contentType(DOCX).body(stockage.lire(nom));
    }

    @GetMapping("/documents/{nom}/configuration")
    public Map<String, Object> configuration(@PathVariable String nom) {
        boolean valide = validation.etat(new NomDocument(nom)).isPresent();
        String base = proprietes.urlServeurPourDocServer() + "/api/documents/" + nom;

        Map<String, Object> editorConfig = new HashMap<>();
        editorConfig.put("callbackUrl", base + "/callback");
        editorConfig.put("lang", "fr");
        editorConfig.put("mode", valide ? "view" : "edit");
        editorConfig.put("user", Map.of("id", "poc-medecin", "name", UTILISATEUR_EDITEUR));
        editorConfig.put("plugins", Map.of(
                "autostart", List.of(proprietes.guidPlugin()),
                "pluginsData", List.of(proprietes.urlServeurPourNavigateur() + "/plugin/config.json")));

        return Map.of(
                "documentType", "word",
                "type", "desktop",
                "width", "100%",
                "height", "100%",
                "document", Map.of(
                        "fileType", "docx",
                        "key", stockage.cleVersion(nom),
                        "title", nom + ".docx",
                        "url", base + "/contenu",
                        "permissions", Map.of("edit", !valide)),
                "editorConfig", editorConfig);
    }

    @PostMapping("/documents/{nom}/callback")
    public Map<String, Integer> callback(@PathVariable String nom, @RequestBody Map<String, Object> corps)
            throws IOException, InterruptedException {
        int statut = ((Number) corps.getOrDefault("status", 0)).intValue();
        if (statut != PRET_A_ENREGISTRER && statut != ENREGISTREMENT_FORCE) {
            return Map.of("error", 0);
        }
        HttpResponse<byte[]> reponse = http.send(
                HttpRequest.newBuilder(URI.create((String) corps.get("url"))).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (reponse.statusCode() != 200) {
            LOG.warn("Téléchargement du document {} impossible : HTTP {}", nom, reponse.statusCode());
            return Map.of("error", 1);
        }
        byte[] docx = reponse.body();
        if (validation.autoriserSauvegarde(new NomDocument(nom), docx)) {
            stockage.remplacer(nom, docx);
            LOG.info("Document {} enregistré (statut {})", nom, statut);
        } else {
            LOG.warn("Sauvegarde ignorée : le compte rendu {} est validé et scellé", nom);
        }
        // error 0 dans les deux cas : sinon OnlyOffice relance la sauvegarde en boucle.
        return Map.of("error", 0);
    }

    // --- Validation ------------------------------------------------------------------------

    public record DemandeValidation(UUID patientId, String requete) {
    }

    @PostMapping("/documents/{nom}/validation")
    public Validation valider(@PathVariable String nom, @RequestBody DemandeValidation demande) {
        Validation v = validation.valider(new NomDocument(nom), demande.patientId(), demande.requete());
        LOG.info("Compte rendu {} validé, empreinte des données {}", nom, v.empreinteDonnees());
        return v;
    }

    @GetMapping("/documents/{nom}/validation")
    public ResponseEntity<Validation> etatValidation(@PathVariable String nom) {
        return ResponseEntity.of(validation.etat(new NomDocument(nom)));
    }

    @ExceptionHandler(CompteRenduDejaValide.class)
    ResponseEntity<Map<String, String>> dejaValide(CompteRenduDejaValide e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(ValidationImpossible.class)
    ResponseEntity<Map<String, String>> impossible(ValidationImpossible e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(RequeteRejetee.class)
    ResponseEntity<Map<String, String>> rejetee(RequeteRejetee e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalide(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler({StockageDocuments.DocumentIntrouvable.class, DocumentIntrouvable.class})
    ResponseEntity<Map<String, String>> introuvable(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflitEtat(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }
}
