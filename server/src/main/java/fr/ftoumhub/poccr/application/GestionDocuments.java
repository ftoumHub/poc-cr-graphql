package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.ActeursInvalides;
import fr.ftoumhub.poccr.domaine.document.ContexteDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.DocumentFige;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import fr.ftoumhub.poccr.domaine.port.FichiersDocuments;
import fr.ftoumhub.poccr.domaine.organisation.Service;
import fr.ftoumhub.poccr.domaine.port.Organisation;
import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Cas d'utilisation : cycle de vie d'un document (fiche + fichier .docx) et de ses acteurs.
 *
 * La fiche et le fichier ne partagent pas de transaction : chaque opération écrit la fiche d'abord,
 * puis le fichier, et rétablit la fiche si le fichier échoue. Un compte rendu validé est figé :
 * ni son nom, ni son patient, ni ses acteurs ne changent.
 */
public class GestionDocuments {

    private final Documents documents;
    private final FichiersDocuments fichiers;
    private final ArchiveValidations validations;
    private final Etablissements etablissements;
    private final ProfessionnelsSante professionnels;
    private final Sejours sejours;
    private final Organisation organisation;
    private final Clock horloge;
    private final Supplier<UUID> identifiants;

    public GestionDocuments(Documents documents, FichiersDocuments fichiers, ArchiveValidations validations,
                            Etablissements etablissements, ProfessionnelsSante professionnels, Sejours sejours,
                            Organisation organisation, Clock horloge, Supplier<UUID> identifiants) {
        this.documents = documents;
        this.fichiers = fichiers;
        this.validations = validations;
        this.etablissements = etablissements;
        this.professionnels = professionnels;
        this.sejours = sejours;
        this.organisation = organisation;
        this.horloge = horloge;
        this.identifiants = identifiants;
    }

    public Optional<Document> document(NomDocument nom) {
        return documents.parNom(nom);
    }

    public List<Document> tous() {
        return documents.tous();
    }

    /**
     * Crée un document vierge. Avec un patient : compte rendu de ce patient (le plugin ne propose plus
     * de choisir le patient). Sans patient : gabarit, dont les liaisons ne portent pas de patient ;
     * il s'instancie par {@link #dupliquer} pour un patient.
     */
    public Document creer(NomDocument nom, String titre, UUID patientId) {
        return creer(nom, titre, patientId, ContexteDocument.AUCUN);
    }

    /** Création dans un contexte (séjour, UF, auteur) : la clé de contexte est figée sur le document. */
    public Document creer(NomDocument nom, String titre, UUID patientId, ContexteDocument contexte) {
        verifierLibre(nom);
        Document document = appliquer(Document.builder()
                .id(identifiants.get())
                .nom(nom)
                .titre(titre)
                .patientId(patientId)
                .etablissementId(etablissements.parDefaut().id())
                .creeLe(Instant.now(horloge))
                .construire(), contexte);
        documents.enregistrer(document);
        try {
            fichiers.creerVierge(nom);
        } catch (RuntimeException e) {
            documents.supprimer(document.id());
            throw e;
        }
        return document;
    }

    /**
     * Importe un .docx comme gabarit (sans patient) : c'est ainsi qu'entrent les gabarits convertis depuis
     * l'application d'origine. Le contenu doit être une archive OOXML (signature ZIP « PK »).
     */
    public Document importerGabarit(NomDocument nom, String titre, byte[] contenu) {
        if (contenu == null || contenu.length < 4 || contenu[0] != 'P' || contenu[1] != 'K') {
            throw new IllegalArgumentException("Le contenu importé n'est pas un fichier .docx : " + nom);
        }
        verifierLibre(nom);
        Document document = Document.builder()
                .id(identifiants.get())
                .nom(nom)
                .titre(titre)
                .etablissementId(etablissements.parDefaut().id())
                .creeLe(Instant.now(horloge))
                .construire();
        documents.enregistrer(document);
        try {
            fichiers.creer(nom, contenu);
        } catch (RuntimeException e) {
            documents.supprimer(document.id());
            throw e;
        }
        return document;
    }

    public Document renommer(NomDocument ancien, NomDocument nouveau) {
        Document document = existant(ancien);
        verifierModifiable(ancien, "le nom");
        verifierLibre(nouveau);
        Document renomme = document.renomme(nouveau);
        documents.enregistrer(renomme);
        try {
            fichiers.renommer(ancien, nouveau);
        } catch (RuntimeException e) {
            documents.enregistrer(document);
            throw e;
        }
        return renomme;
    }

    /**
     * La copie reprend le patient et les acteurs, pas la validation : elle démarre comme un brouillon.
     * Un gabarit (sans patient) peut être copié pour un patient : c'est ainsi qu'il s'instancie. Un document
     * qui a déjà un patient ne se copie que pour ce patient (ses liaisons le désignent : identitovigilance).
     *
     * @param patientId patient de la copie ; null = celui de la source
     */
    public Document dupliquer(NomDocument source, NomDocument nouveau, UUID patientId) {
        return dupliquer(source, nouveau, patientId, ContexteDocument.AUCUN);
    }

    /** Copie placée dans un contexte (instanciation d'un gabarit pour un séjour) ; contexte vide = celui de la source. */
    public Document dupliquer(NomDocument source, NomDocument nouveau, UUID patientId, ContexteDocument contexte) {
        Document original = existant(source);
        if (patientId != null && original.patientId() != null && !patientId.equals(original.patientId())) {
            throw new IllegalArgumentException("Le document " + source
                    + " concerne déjà un patient : il ne peut être copié que pour ce patient");
        }
        verifierLibre(nouveau);
        Document copie = original.copie(identifiants.get(), nouveau, Instant.now(horloge));
        if (patientId != null) {
            copie = copie.avecPatient(patientId);
        }
        copie = appliquer(copie, contexte);
        documents.enregistrer(copie);
        try {
            documents.remplacerActeurs(copie.id(), acteursDe(original));
            fichiers.dupliquer(source, nouveau);
        } catch (RuntimeException e) {
            documents.supprimer(copie.id());
            throw e;
        }
        return copie;
    }

    /**
     * Enregistre le patient du document (appelé par le plugin au verrouillage du patient).
     * Sans effet pour le même patient ; un brouillon peut changer de patient, un compte rendu validé non.
     */
    public Document rattacherPatient(NomDocument nom, UUID patientId) {
        Objects.requireNonNull(patientId, "patientId");
        Document document = existant(nom);
        if (patientId.equals(document.patientId())) {
            return document;
        }
        verifierModifiable(nom, "le patient");
        Document rattache = document.avecPatient(patientId);
        documents.enregistrer(rattache);
        return rattache;
    }

    /**
     * Rattache un brouillon à un autre séjour ou une autre UF (documents créés avant la clé de contexte).
     * L'auteur n'est changé que s'il est donné. Figé après validation.
     */
    public Document definirContexte(NomDocument nom, ContexteDocument contexte) {
        Document document = existant(nom);
        verifierModifiable(nom, "le contexte");
        Document recontextualise = appliquer(document.avecContexte(etablissements.parDefaut().id(), null, null), contexte);
        documents.enregistrer(recontextualise);
        return recontextualise;
    }

    /**
     * Applique une clé de contexte. Le séjour doit appartenir au patient du document (identitovigilance) ;
     * sans UF demandée, c'est l'UF médicale du séjour ; l'établissement est celui du service de l'UF.
     */
    private Document appliquer(Document document, ContexteDocument contexte) {
        if (contexte.estVide()) {
            return document;
        }
        Sejour sejour = null;
        if (contexte.sejourId() != null) {
            if (document.patientId() == null) {
                throw new IllegalArgumentException("Un gabarit (sans patient) n'est rattaché à aucun séjour");
            }
            sejour = sejours.dePatients(Set.of(document.patientId())).getOrDefault(document.patientId(), List.of())
                    .stream().filter(s -> s.id().equals(contexte.sejourId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Le séjour " + contexte.sejourId()
                            + " n'appartient pas au patient du document"));
        }
        String uf = contexte.ufCode() != null ? contexte.ufCode()
                : sejour != null && sejour.ufMedicale() != null ? sejour.ufMedicale().code() : null;
        UUID etablissement = document.etablissementId();
        if (uf != null) {
            String code = uf;
            UniteFonctionnelle unite = organisation.uniteFonctionnelle(code)
                    .orElseThrow(() -> new IllegalArgumentException("Unité fonctionnelle inconnue : " + code));
            etablissement = organisation.service(unite.serviceCode()).map(Service::etablissementId).orElse(etablissement);
        }
        Document resultat = document.avecContexte(etablissement, uf, sejour == null ? null : sejour.id());
        if (contexte.auteurId() != null) {
            if (!professionnels.parIds(Set.of(contexte.auteurId())).containsKey(contexte.auteurId())) {
                throw new IllegalArgumentException("Professionnel de santé inconnu : " + contexte.auteurId());
            }
            resultat = resultat.avecAuteur(contexte.auteurId());
        }
        return resultat;
    }

    public ActeursDocument acteurs(NomDocument nom) {
        return acteursDe(existant(nom));
    }

    public void definirActeurs(NomDocument nom, ActeursDocument acteurs) {
        Document document = existant(nom);
        verifierModifiable(nom, "les signataires et destinataires");
        Set<UUID> inconnus = new HashSet<>(acteurs.professionnels());
        inconnus.removeAll(professionnels.parIds(acteurs.professionnels()).keySet());
        if (!inconnus.isEmpty()) {
            throw new ActeursInvalides("Professionnel de santé inconnu : " + inconnus);
        }
        documents.remplacerActeurs(document.id(), acteurs);
        // POC sans identité d'utilisateur : l'auteur (psCreateur) est, à défaut, le signataire principal
        if (document.auteurId() == null) {
            acteurs.signatairePrincipal().ifPresent(s -> documents.enregistrer(document.avecAuteur(s.professionnelId())));
        }
    }

    /**
     * Crée une fiche pour chaque fichier qui n'en a pas (documents antérieurs à la gestion des fiches),
     * avec le patient lu dans ses liaisons. Idempotent ; renvoie le nombre de fiches créées.
     */
    public int recenser() {
        Set<NomDocument> connus = documents.tous().stream().map(Document::nom).collect(Collectors.toSet());
        UUID etablissement = etablissements.parDefaut().id();
        int crees = 0;
        for (NomDocument nom : fichiers.lister()) {
            if (connus.contains(nom)) {
                continue;
            }
            documents.enregistrer(Document.builder()
                    .id(identifiants.get())
                    .nom(nom)
                    .etablissementId(etablissement)
                    .patientId(fichiers.patientLie(nom).orElse(null))
                    .creeLe(fichiers.derniereModification(nom))
                    .construire());
            crees++;
        }
        return crees;
    }

    private ActeursDocument acteursDe(Document document) {
        return documents.acteursDe(Set.of(document.id())).getOrDefault(document.id(), ActeursDocument.AUCUN);
    }

    private Document existant(NomDocument nom) {
        return documents.parNom(nom).orElseThrow(() -> new DocumentIntrouvable(nom));
    }

    private void verifierLibre(NomDocument nom) {
        if (documents.parNom(nom).isPresent()) {
            throw new IllegalStateException("Un document nommé " + nom + " existe déjà");
        }
    }

    private void verifierModifiable(NomDocument nom, String quoi) {
        if (validations.deDocument(nom).isPresent()) {
            throw new DocumentFige(nom, quoi);
        }
    }
}
