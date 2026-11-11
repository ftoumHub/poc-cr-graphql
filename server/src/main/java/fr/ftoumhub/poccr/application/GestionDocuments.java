package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.DocumentFige;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import fr.ftoumhub.poccr.domaine.port.FichiersDocuments;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;

import java.time.Clock;
import java.time.Instant;
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
    private final Clock horloge;
    private final Supplier<UUID> identifiants;

    public GestionDocuments(Documents documents, FichiersDocuments fichiers, ArchiveValidations validations,
                            Etablissements etablissements, Clock horloge, Supplier<UUID> identifiants) {
        this.documents = documents;
        this.fichiers = fichiers;
        this.validations = validations;
        this.etablissements = etablissements;
        this.horloge = horloge;
        this.identifiants = identifiants;
    }

    public Optional<Document> document(NomDocument nom) {
        return documents.parNom(nom);
    }

    public Document creer(NomDocument nom, String titre) {
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
            fichiers.creerVierge(nom);
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

    /** La copie reprend le patient et les acteurs, pas la validation : elle démarre comme un brouillon. */
    public Document dupliquer(NomDocument source, NomDocument nouveau) {
        Document original = existant(source);
        verifierLibre(nouveau);
        Document copie = original.copie(identifiants.get(), nouveau, Instant.now(horloge));
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

    public ActeursDocument acteurs(NomDocument nom) {
        return acteursDe(existant(nom));
    }

    public void definirActeurs(NomDocument nom, ActeursDocument acteurs) {
        Document document = existant(nom);
        verifierModifiable(nom, "les signataires et destinataires");
        documents.remplacerActeurs(document.id(), acteurs);
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
