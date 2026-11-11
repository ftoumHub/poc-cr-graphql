package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Port secondaire : fiches des documents et leurs acteurs. */
public interface Documents {

    Optional<Document> parNom(NomDocument nom);

    Map<UUID, Document> parIds(Set<UUID> ids);

    List<Document> tous();

    /** Crée ou met à jour la fiche (même identifiant). */
    void enregistrer(Document document);

    /** Supprime la fiche et ses acteurs. */
    void supprimer(UUID documentId);

    /** Acteurs de chaque document demandé ; {@link ActeursDocument#AUCUN} pour un document sans acteur. */
    Map<UUID, ActeursDocument> acteursDe(Set<UUID> documentIds);

    /** Remplace tous les acteurs du document. */
    void remplacerActeurs(UUID documentId, ActeursDocument acteurs);
}
